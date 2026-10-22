# 03 数据库设计

> 状态：M0 冻结，V1–V5 已随 M1–M6 落地。业务表共 6 张；表结构变更必须通过新
> Flyway 脚本完成。
>
> 生产库 MySQL 8，测试/演示库 H2（MySQL 模式）。DDL 按厂商分目录维护，
> 语义一致、方言不同，禁止用 H2 专有语法污染 MySQL 脚本。

## 1. 设计原则

1. **会话 entry 是唯一事实源。** 消息、压缩摘要、审批决策、运行摘要都是 entry；
   不另建对话表、摘要表、usage 表。
2. **append-only。** entry 只插入、不更新、不删除；分支只移动 `pi_session.leaf_entry_id`。
3. **ULID 主键。** `CHAR(26)`，时间有序，避免自增 ID 暴露规模，也便于按时间范围扫描。
4. **乐观锁。** 会话、模型档案、工具策略带 `version`；追加 entry 时对 session 做 CAS。
5. **短事务。** LLM 与工具执行期间不持有事务；每次 append 是独立事务。
6. **逻辑外键优先。** 只有会话→entry、会话→审批使用物理外键级联；
   entry 的树形父节点、模型档案引用、run 引用都是逻辑关联，避免跨生命周期阻塞。

## 2. ER 关系

```text
                        ┌──────────────────┐
                        │   pi_session     │
                        │  id (PK)         │
                        │  parent_session  │──┐ 自关联 SET NULL
                        │  root_session    │◄─┘
                        │  leaf_entry_id   │──────► pi_session_entry.id（逻辑）
                        └───┬──────────┬───┘
                            │1        │1
                            │         │
                       N    │         │    N
              ┌─────────────▼──┐   ┌──▼─────────────────┐
              │ pi_session_entry│   │ pi_approval_request│
              │ id (PK)         │   │ id (PK)            │
              │ session_id (FK) │   │ session_id (FK)    │
              │ parent_id (逻辑)│   │ run_id (逻辑)      │
              └─────────────────┘   └────────────────────┘

  pi_model_profile ◄──── 逻辑引用 ──── pi_session.model_profile_id
  pi_tool_policy       独立（scope + workspace_key + tool_name）
  pi_plugin            独立（插件安装记录）
```

| 关系 | 基数 | 物理约束 | 说明 |
|---|---|---|---|
| session → entry | 1:N | `session_id` FK，`ON DELETE CASCADE` | 删除会话即删除日志 |
| session → approval | 1:N | `session_id` FK，`ON DELETE CASCADE` | 待办投影随会话清理 |
| session → session | 1:N | `parent_session_id` 自关联，`ON DELETE SET NULL` | 删除父会话不删除子会话 |
| model_profile → session | 1:N | 无 | 模型档案下线不破坏历史会话 |
| entry.parent_id | 树 | 无 | 同会话树形关系，应用层校验 |
| plugin / policy | 独立 | 无 | 通过 workspace_key/tool_name 逻辑关联 |

> 父会话删除后，子会话的 `parent_session_id` 为 NULL，但 `origin/depth/root_session_id` 保留。
> 领域层的 `SessionIdentity` 允许这种“孤儿”形态在重建时出现；创建新子会话仍必须提供父会话。

## 3. MySQL DDL

字符集统一 `utf8mb4`，排序规则 `utf8mb4_0900_ai_ci`，引擎 InnoDB。
所有时间列存 UTC，精度毫秒；应用层用 `Instant`，JDBC 时区固定 UTC。

### 3.1 `V1__session.sql`

```sql
CREATE TABLE pi_session (
    id                 CHAR(26)     NOT NULL,
    parent_session_id  CHAR(26)     NULL,
    root_session_id    CHAR(26)     NOT NULL,
    fork_from_entry_id CHAR(26)     NULL,
    depth              SMALLINT     NOT NULL DEFAULT 0,
    origin             VARCHAR(16)  NOT NULL,
    title              VARCHAR(255) NOT NULL,
    cwd                VARCHAR(1024) NOT NULL,
    model_profile_id   VARCHAR(64)  NOT NULL,
    thinking_level     VARCHAR(16)  NOT NULL DEFAULT 'OFF',
    sandbox_json       TEXT         NOT NULL,
    status             VARCHAR(16)  NOT NULL,
    leaf_entry_id      CHAR(26)     NULL,
    version            BIGINT       NOT NULL DEFAULT 0,
    created_at         DATETIME(3)  NOT NULL,
    updated_at         DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_session_parent (parent_session_id),
    KEY idx_session_root (root_session_id),
    KEY idx_session_status (status, updated_at),
    CONSTRAINT fk_session_parent
        FOREIGN KEY (parent_session_id) REFERENCES pi_session (id)
        ON DELETE SET NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE pi_session_entry (
    id              CHAR(26)    NOT NULL,
    session_id      CHAR(26)    NOT NULL,
    seq             BIGINT      NOT NULL,
    parent_id       CHAR(26)    NULL,
    entry_type      VARCHAR(32) NOT NULL,
    payload_version INT         NOT NULL,
    payload_json    LONGTEXT    NOT NULL,
    token_estimate  INT         NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_entry_session_seq (session_id, seq),
    KEY idx_entry_session_parent (session_id, parent_id),
    KEY idx_entry_session_type (session_id, entry_type),
    CONSTRAINT fk_entry_session
        FOREIGN KEY (session_id) REFERENCES pi_session (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

说明：

- 根会话 `root_session_id = id`；fork/subagent 继承父的 root。
- `leaf_entry_id` 不建物理外键：entry 与 session 互相引用会形成插入顺序死锁；
  由应用层校验“leaf 属于本会话”。
- `parent_id` 不建物理外键：树形日志允许恢复流程修复悬空节点；
  `(session_id, parent_id)` 索引支撑分支回溯。
- `seq` 是会话内单调序号，用于排序与断线重放；唯一键是并发写的最后防线。

### 3.2 `V2__model_profile.sql`

```sql
CREATE TABLE pi_model_profile (
    id                VARCHAR(64)  NOT NULL,
    display_name      VARCHAR(128) NOT NULL,
    provider          VARCHAR(32)  NOT NULL,
    base_url          VARCHAR(512) NOT NULL DEFAULT '',
    api_key_env       VARCHAR(128) NOT NULL DEFAULT '',
    model_name        VARCHAR(128) NOT NULL,
    context_window    INT          NOT NULL,
    max_output_tokens INT          NOT NULL,
    capabilities_json TEXT         NOT NULL,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    version           BIGINT       NOT NULL DEFAULT 1,
    created_at        DATETIME(3)  NOT NULL,
    updated_at        DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_model_profile_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- `api_key_env` **只存环境变量名**（如 `DEEPSEEK_API_KEY`），任何情况下不存密钥值；
  `FAKE` provider 允许空串，因此列是 `NOT NULL DEFAULT ''` 而不是 NULL，避免 H2/MySQL
  对 NULL 语义的差异。
- `capabilities_json` 只保存 `streaming/toolCalls/thinking`；上下文窗口与输出上限是
  查询和校验都要用的数值，保留为独立列。
- `version` 从 1 开始，应用层用读到的旧版本做 CAS；H2 迁移脚本使用 `TIMESTAMP(3)`
  与 `CLOB`，索引单独 `CREATE INDEX`，其余语义与 MySQL 一致。

### 3.3 `V3__tool_policy.sql`

```sql
CREATE TABLE pi_tool_policy (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    scope          VARCHAR(16)  NOT NULL,
    workspace_key  CHAR(64)     NOT NULL,
    tool_name      VARCHAR(128) NOT NULL,
    effect         VARCHAR(16)  NOT NULL,
    approval_mode  VARCHAR(16)  NOT NULL,
    risk_level     VARCHAR(16)  NOT NULL,
    timeout_ms     INT          NULL,
    config_json    TEXT         NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 1,
    created_at     DATETIME(3)  NOT NULL,
    updated_at     DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_policy (scope, workspace_key, tool_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- `workspace_key = sha256(normalized cwd)`，避免 1024 字符路径进入唯一索引；
- USER 作用域使用 `WorkspaceKey.GLOBAL`（64 个 `0`），而不是空串——空串无法表达
  “全局规则也参与 `workspace_key` 唯一键”的语义，且与 `CHAR(64)` 的长度约束冲突；
- `version` 从 1 开始，与模型档案一致；仓储 `UPDATE ... WHERE id = ? AND version = ?`，
  成功后 `version = version + 1`；
- `timeout_ms` 为 NULL 表示沿用 `ToolDescriptor` 的默认超时；`config_json` 至少保存 `{}`。

H2 迁移使用 `CLOB`、`TIMESTAMP(3)` 与 `CONSTRAINT uk_tool_policy UNIQUE (...)`，
其余语义与 MySQL 一致。

### 3.4 `V4__plugin.sql`

```sql
CREATE TABLE pi_plugin (
    id           VARCHAR(128)  NOT NULL,
    version      VARCHAR(32)   NOT NULL,
    api_version  VARCHAR(16)   NOT NULL,
    jar_path     VARCHAR(1024) NOT NULL,
    checksum     CHAR(64)      NOT NULL,
    status       VARCHAR(16)   NOT NULL,
    config_json  TEXT          NOT NULL,
    last_error   VARCHAR(1024) NULL,
    installed_at DATETIME(3)   NOT NULL,
    updated_at   DATETIME(3)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_plugin_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

M5 已落地。一个插件 id 在库中只有一行；M5 **没有升级/覆盖接口**，重复安装返回
409，修改插件后需要重启进程并重新安装。`jar_path` 与 `checksum` 是启用时重新校验
文件一致性的依据；`config_json` 当前只保存不读取。启动时只恢复 `ACTIVE` 记录，
加载失败会把该行置为 `FAILED` 并写入 `last_error`，不影响应用启动。

### 3.5 `V5__approval_request.sql`

```sql
CREATE TABLE pi_approval_request (
    id                  CHAR(26)     NOT NULL,
    session_id          CHAR(26)     NOT NULL,
    run_id              CHAR(26)     NOT NULL,
    tool_call_id        VARCHAR(64)  NOT NULL,
    tool_name           VARCHAR(128) NOT NULL,
    args_digest         CHAR(64)     NOT NULL,
    args_redacted_json  TEXT         NOT NULL,
    risk_level          VARCHAR(16)  NOT NULL,
    status              VARCHAR(16)  NOT NULL,
    reason              VARCHAR(1024) NULL,
    decided_by          VARCHAR(64)  NULL,
    decided_at          DATETIME(3)  NULL,
    expires_at          DATETIME(3)  NOT NULL,
    created_at          DATETIME(3)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_approval_run_call (run_id, tool_call_id),
    KEY idx_approval_session (session_id, created_at),
    KEY idx_approval_status_expiry (status, expires_at),
    CONSTRAINT fk_approval_session
        FOREIGN KEY (session_id) REFERENCES pi_session (id)
        ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

该表是“待办投影”，不是审计事实源：审批决策必须同时追加 `APPROVAL` entry。
`args_digest = sha256(canonicalJson(args))`，用于检测 UI 展示参数与实际执行参数不一致。

## 4. H2 方言差异

H2 使用 `MODE=MySQL`，但以下写法仍需区分，避免运行时解析失败：

| MySQL | H2 | 说明 |
|---|---|---|
| `DATETIME(3)` | `TIMESTAMP(3)` | 语义相同 |
| `LONGTEXT` | `CLOB` | H2 的文本大对象 |
| `ENGINE=InnoDB ...` | 省略 | H2 无表空间/引擎 |
| 表内 `KEY idx (...)` | 独立 `CREATE INDEX` | 兼容性更稳 |
| `COLLATE utf8mb4_0900_ai_ci` | 省略 | H2 排序规则由 URL 控制 |

H2 脚本位于 `pi-java-app/src/main/resources/db/migration/h2/`，
MySQL 脚本位于 `.../mysql/`；Flyway 按 profile 选择 `locations`，互不混用。

## 5. Session entry payload

`payload_json` 的顶层结构由 `payload_version` 解释，当前为 1。
只允许新增字段，禁止改变已有字段语义；读取方必须忽略未知字段。

### 5.1 消息

```json
{
  "role": "assistant",
  "content": [
    {"type": "text", "text": "我先读取 pom.xml"},
    {"type": "toolCall", "id": "call_1", "name": "read", "arguments": {"path": "pom.xml"}}
  ],
  "model": {"provider": "deepseek", "modelName": "deepseek-chat"},
  "usage": {"input": 1200, "output": 80, "cacheRead": 0, "cacheWrite": 0, "totalTokens": 1280},
  "stopReason": "TOOL_USE",
  "timestamp": "2026-10-21T06:00:00Z"
}
```

工具结果：

```json
{
  "role": "toolResult",
  "toolCallId": "call_1",
  "toolName": "read",
  "content": [{"type": "text", "text": "<project>..."}],
  "isError": false,
  "details": {"bytes": 2048, "truncated": false},
  "timestamp": "2026-10-21T06:00:01Z"
}
```

### 5.2 压缩

```json
{
  "summary": "## Goal\n...",
  "firstKeptEntryId": "01J...",
  "tokensBefore": 48210,
  "details": {
    "readFiles": ["src/main/java/..."],
    "modifiedFiles": ["pom.xml"]
  }
}
```

### 5.3 审批决策

```json
{
  "approvalId": "01J...",
  "toolCallId": "call_2",
  "toolName": "write",
  "decision": "APPROVED",
  "reason": "允许修改 README",
  "decidedBy": "local-user"
}
```

`decision` 记录终态字符串（`APPROVED/DENIED/EXPIRED/CANCELLED`）；
HTTP 用户决策写 `local-user`，过期与取消写 `system`。决策同时更新
`pi_approval_request` 投影并追加本 entry，二者在同一个短事务内完成。

### 5.4 运行摘要

```json
{
  "runId": "01J...",
  "stopReason": "STOP",
  "usage": {"input": 5000, "output": 600, "cacheRead": 0, "cacheWrite": 0, "totalTokens": 5600},
  "turns": 3,
  "toolCalls": 2,
  "durationMs": 8421,
  "errorMessage": null
}
```

## 6. 写入协议

追加一条 entry 的固定顺序：

```text
BEGIN
  1. SELECT ... FROM pi_session WHERE id=? FOR UPDATE     -- 可选，进程内锁已先保证单写者
  2. SELECT COALESCE(MAX(seq),0)+1 FROM pi_session_entry WHERE session_id=?
  3. INSERT pi_session_entry(...)
  4. UPDATE pi_session
        SET leaf_entry_id=?, version=version+1, updated_at=?
      WHERE id=? AND version=?
  5. 若更新行数为 0 -> 抛并发冲突，回滚
COMMIT
```

约束：

- 事务内不做网络调用、不执行工具、不等待审批；
- 同一会话由进程内 `SessionLockRegistry` 串行化，数据库唯一键与 CAS 兜底；
- 启动恢复时，若发现 toolCall 无配对结果，补写 `isError=true` 的 toolResult；
  若发现 RUNNING 但无活跃 run，补写 `RUN_SUMMARY(stopReason=ERROR)`。

## 7. 为什么只有 6 张表

| 候选表 | 处理方式 | 原因 |
|---|---|---|
| 压缩摘要 | `COMPACTION` entry | 摘要属于会话历史，必须与分支绑定 |
| 审批决策 | `APPROVAL` entry + `pi_approval_request` 投影 | 审计以日志为准，投影只服务待办查询 |
| 运行摘要 | `RUN_SUMMARY` entry | Run 不是事实源，崩溃后可从 entry 恢复语义 |
| 子代理 | 复用 `pi_session` 的 parent/root/depth/origin | 子代理就是子会话，不需要单独模型 |
| Skills | 文件系统 | 用户可直接编辑，不需要数据库生命周期 |
| 工作区 | session 的 cwd + sandbox 快照 | v0.1 没有工作区生命周期 |
| token 统计 | assistant payload + session 汇总 | 不做计费系统，避免额外写入放大 |
| 插件工具 | 内存快照 + `pi_plugin` | 工具是代码产物，不需要每工具一行 |

## 8. 迁移规范

- 文件命名：`V<序号>__<主题>.sql`，序号全局递增，主题小写下划线；
- 已提交脚本禁止修改；修复使用新版本；
- 每个厂商目录保持相同版本号与主题；
- `clean` 在生产禁用（`spring.flyway.clean-disabled=true`）；
- 启动时 Flyway 校验 checksum，迁移失败应用拒绝启动；
- 测试库每次启动执行全部迁移，验证 H2 与 MySQL 的语义一致性；
- M1 落地 V1，M2 落 V2，M3 落 V3，M5 落 V4（已实现），M6 落 V5。

## 9. 索引与容量

- 会话列表：`(status, updated_at)` 支撑“最近活跃”排序；
- 分支回溯：`(session_id, parent_id)`；
- 按类型审计：`(session_id, entry_type)`；
- 审批待办：`(status, expires_at)`，过期扫描不扫全表；
- entry payload 是主要增长源；v0.1 不做自动归档，文档提示定期备份与手动归档；
- 单条 payload 上限由应用层限制；M3 只读工具按 60k–80k 字符截断（`read/grep` 80k，
  `ls/glob` 60k），M6 的 `bash` 才把完整输出落临时文件并只在 entry 中保留路径。

## 10. 相关文档

- [领域模型](02-domain-model.md)
- [运行时与事件](04-runtime-and-events.md)
- [测试与质量](07-testing-and-quality.md)
