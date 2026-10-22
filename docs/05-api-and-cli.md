# 05 API 与 CLI

> 状态：M6 已实现会话/运行 REST、SSE、模型与工具查询、审批与策略、插件管理；
> 分支/fork/compact、Skills 与 CLI 是后续里程碑。
> HTTP 前缀固定 `/api/v1`；CLI 命令名固定为 `pi`。
> Web 与 CLI 共享应用层用例，任何业务规则不得写在 Controller 或命令类中。

## 1. 通用约定

- 请求/响应：`application/json; charset=UTF-8`；SSE：`text/event-stream`；
- 时间：ISO-8601 UTC 字符串（`Instant.toString()`），不做本地时区转换；
- ID：ULID 字符串，26 位；不暴露自增主键（策略表除外）；
- 字段命名：camelCase；未知字段忽略；DTO 不含领域对象；
- 分页：M4 只有 `limit`，没有 cursor；会话列表默认 50 条；
- 鉴权：v0.1 **无鉴权**，默认只监听 `127.0.0.1`，禁止直接暴露公网；
- 幂等：`POST /sessions/{id}/abort` 对没有活跃 run 的会话返回 404，不重复写 entry；
- 请求 ID 与日志关联字段尚未实现，M9 打磨阶段补充。

### 1.1 错误格式

当前的错误响应只有稳定错误码与可读消息：

```json
{
  "code": "AGENT_BUSY",
  "message": "并发运行数已达上限，请稍后重试"
}
```

| HTTP | 实际 code | 场景 |
|---|---|---|
| 400 | `INVALID_ARGUMENT` | 参数缺失、ULID/路径非法、请求体不是合法 JSON |
| 404 | `SESSION_NOT_FOUND` / `RUN_NOT_FOUND` / `MODEL_NOT_FOUND` / `ENTRY_NOT_FOUND` / `PLUGIN_NOT_FOUND` | 资源不存在 |
| 409 | `RUN_ALREADY_ACTIVE` / `CONCURRENT_MODIFICATION` / `MODEL_ALREADY_EXISTS` / `MODEL_DISABLED` / `INVALID_STATE_TRANSITION` / `SESSION_ARCHIVED` / `PLUGIN_ALREADY_EXISTS` / `PLUGIN_INVALID_STATE` / `PLUGIN_TOOL_CONFLICT` | 状态或并发冲突 |
| 422 | `PLUGIN_INCOMPATIBLE` / `PLUGIN_LOAD_FAILED` | 插件 jar/API 不兼容或加载失败 |
| 429 | `AGENT_BUSY` | 全局并发 run 已满 |
| 500 | 框架默认错误体 | 未映射异常；不伪装成领域错误 |

工具被策略拒绝、工具执行失败不产生 HTTP 错误，而是 `isError=true` 的 toolResult
与 `tool.completed` 事件，由模型自行处理。

## 2. 会话 API

### 2.1 新建会话

```http
POST /api/v1/sessions
Content-Type: application/json

{
  "title": "重构登录模块",
  "cwd": "D:/code/demo",
  "modelProfileId": "fake-demo",
  "thinkingLevel": "OFF"
}
```

- `thinkingLevel` 可省略，默认 `OFF`；取值 `OFF / LOW / MEDIUM / HIGH`；
- `cwd` 必须存在且为目录，服务端规范化后作为沙箱根；
- 根会话的 `parentSessionId` 为 null、`rootSessionId` 等于自身、`depth=0`、`origin=USER`。

响应 `201`（字段即 `SessionResponse`）：

```json
{
  "id": "01J...",
  "parentSessionId": null,
  "rootSessionId": "01J...",
  "title": "重构登录模块",
  "cwd": "D:\\code\\demo",
  "modelProfileId": "fake-demo",
  "thinkingLevel": "OFF",
  "status": "IDLE",
  "leafEntryId": null,
  "depth": 0,
  "origin": "USER",
  "version": 0
}
```

### 2.2 列表与详情

```http
GET /api/v1/sessions?cwd=D:/code/demo&limit=20
GET /api/v1/sessions/{sessionId}
```

- 列表按 `updated_at DESC`，`cwd` 可省略，`limit` 默认 50；
- 列表与详情使用同一个 `SessionResponse`，不返回 entry 正文；
- 删除接口与分支摘要尚未实现。

### 2.3 分支 entry

```http
GET /api/v1/sessions/{sessionId}/entries?leafId={entryId}
```

省略 `leafId` 时使用会话当前 leaf；响应是**裸数组**，按 `seq` 升序。每个 entry 使用
稳定 envelope，前端不需要理解领域 payload 的 Java 类型：

```json
[
  {
    "id": "01J...",
    "parentId": null,
    "seq": 1,
    "type": "MESSAGE",
    "createdAt": "2026-10-21T06:00:01Z",
    "body": {
      "role": "user",
      "content": [{"type": "text", "text": "你好"}],
      "toolCallId": null,
      "toolName": null,
      "isError": null,
      "extra": {}
    }
  }
]
```

`body.role` 为 `user / assistant / tool / system`；`body.extra` 承载压缩、运行摘要、
审批、模型切换等结构化信息。`view=tree` 尚未实现。

### 2.4 Fork（应用用例已实现，HTTP 未暴露）

> 领域与应用层已有 `SessionApplicationService.fork`，M4 尚未提供 HTTP 端点；
> 以下为后续契约草案。

```http
POST /api/v1/sessions/{sessionId}/fork?entryId={entryId}
```

- 不传 `entryId` 时从当前 leaf fork；
- 新会话 `origin=FORK`，`parent_session_id=源会话`，`root_session_id=源根`，`depth+1`；
- 复制源分支到 fork 点的 entry 引用语义由应用层处理：v0.1 只复制**会话元数据**，
  新会话从空日志开始，并在首条 `BRANCH_SUMMARY` entry 中写入“从哪个节点 fork”；
  这样不复制历史，也不会让 fork 后的 token 成本失控；
- 返回新会话详情。

> 说明：与 pi 的“原地切分支”不同，pi-java 的 fork 创建**新会话**；
> 原地移动 leaf 通过 `POST /sessions/{id}/branch` 完成。

### 2.5 切换分支（应用用例已实现，HTTP 未暴露）

> `SessionApplicationService.moveBranch` 已存在，M4 尚未提供 HTTP 端点。

```http
POST /api/v1/sessions/{sessionId}/branch
Content-Type: application/json

{"entryId": "01J...", "summarize": true}
```

- `entryId` 必须属于该会话且不是 toolResult；
- `summarize=true` 时先为被离开的分支生成 `BRANCH_SUMMARY` entry；
- 只移动 `leaf_entry_id`，不修改历史 entry；
- 有活跃 run 时返回 409。

### 2.6 发送消息（SSE）

```http
POST /api/v1/sessions/{sessionId}/messages/stream
Content-Type: application/json
Accept: text/event-stream

{"text": "读取 pom.xml 并解释依赖"}
```

响应 `200 text/event-stream`。服务端先同步完成“追加用户消息 + 预留 run”，再返回
emitter；run 在虚拟线程执行，响应体随事件持续输出，终态后关闭：

```text
id: 01J-run:1
event: run.started
data: {"seq":1,"runId":"01J-run","sessionId":"01J...","type":"run.started","at":"2026-10-21T06:00:00Z","data":{"runId":"01J-run","modelProfileId":"fake-demo","modelName":"fake-demo","maxTurns":20,"startedAt":"..."}}

id: 01J-run:2
event: message.delta
data: {"seq":2,...,"type":"message.delta","data":{"kind":"text","text":"我先"}}
```

- 事件类型与 data 字段见 [04 运行时与事件](04-runtime-and-events.md)；
- 同一会话已有活跃 run 返回 409 `RUN_ALREADY_ACTIVE`；全局并发已满返回 429 `AGENT_BUSY`；
- `Last-Event-ID` 只对 `GET /runs/{runId}/events` 生效；POST 不能重放已创建的消息，
  断线后用 runId 走重连接口。

### 2.7 活跃 run 与断线重连

```http
GET /api/v1/sessions/{sessionId}/run
GET /api/v1/runs/{runId}
GET /api/v1/runs/{runId}/events
Last-Event-ID: 01J-run:17
Accept: text/event-stream
```

- `GET /sessions/{id}/run`：有活跃 run 返回 `{runId, sessionId}`，否则 `204`；
  页面刷新后用它在“新建流”和“重连流”之间选择；
- `GET /runs/{runId}`：返回 run 快照 `runId/sessionId/status/stopReason/turns/toolCalls/usage/startedAt/finishedAt/durationMs/errorMessage`；
- `Last-Event-ID` 支持 `runId:seq` 与纯数字；空值从头回放；
- 命中缓冲：回放 `seq+1` 起的事件，再持续推送；已结束且缓冲仍在：回放后关闭；
- 起点已淘汰：先收到 `replay.gap`，客户端调用 entry 接口重拉分支；
- run 不存在或事件通道已清理：404 `RUN_NOT_FOUND`。

### 2.8 取消

```http
POST /api/v1/sessions/{sessionId}/abort
```

响应 `202` 与 `{runId, sessionId}`；无活跃 run 返回 404 `RUN_NOT_FOUND`。
取消是协作式的：当前 LLM 流被关闭，已输出文本保留，随后写
`RUN_SUMMARY(stopReason=CANCELLED)`。

> 手动压缩 `POST /compact` 在 M7 提供。

## 3. 审批 API（M6）

```http
GET  /api/v1/approvals?sessionId=01J...&status=PENDING
GET  /api/v1/approvals/{approvalId}
POST /api/v1/approvals/{approvalId}/decision
Content-Type: application/json

{"decision": "APPROVE", "reason": "允许修改 README"}
```

`sessionId` 必填，`status` 可选（缺省返回全部状态）。审批 DTO：

```json
{
  "approvalId": "01J...",
  "sessionId": "01J...",
  "runId": "01J...",
  "toolCallId": "call_2",
  "toolName": "write",
  "riskLevel": "HIGH",
  "argumentsDigest": "64 位小写十六进制",
  "arguments": {"path": "README.md", "content": "..."},
  "status": "PENDING",
  "expiresAt": "2026-10-21T06:30:00Z",
  "createdAt": "2026-10-21T06:00:00Z"
}
```

- `arguments` 是脱敏副本（敏感键值替换为 `***`）；`argumentsDigest` 基于原始参数，
  只出现在 HTTP 详情中，SSE 事件不包含它；
- `decision` 只能是 `APPROVE` / `DENY`（大小写不敏感）；决策者由服务端固定为
  `local-user`，请求体不能指定 `decidedBy`；
- 决策成功返回终态 DTO，增加 `decidedBy`、`decidedAt` 与可选 `reason`；
- 非 PENDING 或并发 CAS 失败返回 409 `APPROVAL_INVALID_STATE`；
  审批已过期时先迁移为 EXPIRED，再返回 409；
- 决策成功后追加 `APPROVAL` entry，并在事务提交后推送 `approval.resolved`；
- `approval.requested` / `approval.resolved` 是运行 SSE 事件，不单独提供订阅端点。

## 4. 模型 API

```http
GET  /api/v1/models
GET  /api/v1/models/{id}
POST /api/v1/models
PUT  /api/v1/models/{id}
POST /api/v1/models/{id}/enable
POST /api/v1/models/{id}/disable
```

创建请求（更新使用同一 endpoint 结构，路径中的 `{id}` 为准）：

```json
{
  "id": "deepseek-chat",
  "displayName": "DeepSeek Chat",
  "endpoint": {
    "provider": "OPENAI_COMPATIBLE",
    "baseUrl": "https://api.deepseek.com",
    "apiKeyEnv": "DEEPSEEK_API_KEY",
    "modelName": "deepseek-chat",
    "contextWindow": 65536,
    "maxOutputTokens": 8192,
    "capabilities": {"streaming": true, "toolCalls": true, "thinking": false}
  }
}
```

响应在请求字段之外增加 `enabled` 与 `version`，**任何响应都不返回密钥值**：

```json
{
  "id": "deepseek-chat",
  "displayName": "DeepSeek Chat",
  "endpoint": {"provider": "OPENAI_COMPATIBLE", "...": "..."},
  "enabled": true,
  "version": 1
}
```

约束与错误：

- `apiKeyEnv` 只接受环境变量名；服务端校验 `[A-Z][A-Z0-9_]{0,127}`，不接收密钥字面量；
- `contextWindow`、`maxOutputTokens` 必须为正且输出不超过上下文；`baseUrl` 必须为
  http/https（`FAKE` 除外）；
- `POST` 遇到重复 id 返回 409 `MODEL_ALREADY_EXISTS`；`PUT` 使用服务端读到的当前版本
  做 CAS，并发冲突返回 409 `CONCURRENT_MODIFICATION`；
- 未知 provider 返回 400 `INVALID_ARGUMENT`；空请求体、非法 JSON 返回 400；
- 找不到档案返回 404 `MODEL_NOT_FOUND`；停用档案返回 409 `MODEL_DISABLED`（当用例要求
  启用档案时）；
- `enable/disable` 幂等：重复调用仍返回当前档案与版本。

## 5. 工具与策略 API

### 5.1 工具目录

启用插件后，插件工具与内置工具出现在同一列表中：

```http
GET /api/v1/tools?cwd=D:/code/demo
```

响应示例（截断）：

```json
[
  {
    "name": "read",
    "description": "Read a UTF-8 text file with line numbers.",
    "inputSchema": "{\"type\":\"object\",\"properties\":{...},\"required\":[\"path\"]}",
    "risk": "LOW",
    "executionMode": "PARALLEL",
    "timeoutMs": 10000,
    "effect": "ALLOW",
    "requiresApproval": false
  }
]
```

- `cwd` 用于计算 `workspace_key`（`sha256(normalized cwd)`）；省略时使用进程当前目录，
  USER 规则通过 `GLOBAL` 键参与解析；
- 返回完整 `inputSchema`，供前端展示参数与后续 LLM 请求复用；不是只返回摘要；
- `effect` 是当前工作区解析后的结果（`ALLOW / ASK / DENY`），写入磁盘前会重新解析，
  不信任客户端回传；
- `cwd` 非法（如含 NUL）返回 400 `INVALID_ARGUMENT`。

### 5.2 策略管理（M6）

```http
GET /api/v1/tool-policies?cwd=D:/code/demo
PUT /api/v1/tool-policies
Content-Type: application/json

{
  "scope": "WORKSPACE",
  "cwd": "D:/code/demo",
  "toolName": "write",
  "effect": "ASK",
  "approvalMode": "ONCE",
  "riskLevel": "HIGH",
  "timeoutMs": 30000,
  "config": {}
}
```

- `GET` 返回工作区规则与 USER 全局规则（`workspaceKey` 为 64 个 `0`），字段包含
  `id/scope/workspaceKey/toolName/effect/approvalMode/riskLevel/timeoutMs/config/version`；
- `PUT` 是 upsert：同一 `(scope, workspace_key, tool_name)` 已存在时更新并递增 `version`；
  `riskLevel` 可省略（沿用工具默认风险），`config` 可为空但顶层值不能为 null；
- 策略解析优先级 `WORKSPACE > USER > 风险默认`；`DENY` 命中后不会被更宽规则覆盖；
- 未知 scope/effect/approvalMode/riskLevel、空 `toolName`、非法 `cwd` 返回 400
  `INVALID_ARGUMENT`；工具不存在返回 404 `TOOL_NOT_FOUND`；
- `approvalMode` 当前只做持久化，执行器仍按 `effect` 每次询问。

## 6. 插件 API（M5）

```http
GET  /api/v1/plugins
POST /api/v1/plugins
POST /api/v1/plugins/{id}/enable
POST /api/v1/plugins/{id}/disable
```

安装请求只接受宿主可读的**本地 jar 路径**（没有上传接口）：

```json
{
  "jarPath": "D:/code/pi-java/examples/pi-plugin-example/target/pi-plugin-example-0.1.0-SNAPSHOT.jar",
  "config": {}
}
```

`config` 必须是 JSON 对象，可省略；M5 只保存不使用，留给 v0.2 的插件配置。

响应 `201`：

```json
{
  "id": "com.piagent.example.hello",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "jarPath": "D:\\code\\pi-java\\examples\\pi-plugin-example\\target\\pi-plugin-example-0.1.0-SNAPSHOT.jar",
  "status": "INSTALLED",
  "lastError": null,
  "installedAt": "2026-10-22T08:00:00Z",
  "updatedAt": "2026-10-22T08:00:00Z"
}
```

- 响应不返回配置原文与完整校验和；
- 安装只校验并落库，不执行插件代码；enable 才创建类加载器并调用 `start`；
- 安装时校验：jar 存在且 ≤ 64 MB、清单完整、入口类存在、API 主版本为 1、
  插件 id 未安装、jar 内不含 `com/piagent/plugin/api` 类；
- enable：重新检查 jar 与安装记录（路径、版本、API 版本、SHA-256 一致），
  加载失败返回 422 `PLUGIN_LOAD_FAILED` 并落库为 `FAILED`；
- disable：立即从实时工具目录移除并置 `DISABLED`；**不等待**活跃调用归零，
  已开始的 run 继续用旧快照与旧类加载器完成；
- enable/disable 对目标状态幂等；从 `FAILED`、`DISABLED`、`INSTALLED` 均可再次 enable；
- 重复安装返回 409 `PLUGIN_ALREADY_EXISTS`；未知插件返回 404 `PLUGIN_NOT_FOUND`；
- 应用重启后只自动恢复 `ACTIVE` 记录，恢复失败的插件置 `FAILED` 并记录 `lastError`。

停用后插件工具从 `GET /api/v1/tools` 消失；新 run 调用该工具得到
`工具未注册: <name>` 的 toolResult，run 本身仍正常完成。

## 7. 系统信息、Skills 与健康检查

M4 已实现：

```http
GET /api/v1/system/info
```

```json
{
  "version": "0.1.0-SNAPSHOT",
  "workspaceRoot": "D:\\code\\pi-java",
  "osName": "Windows 11"
}
```

Web UI 用它获取默认工作目录，避免把开发者机器路径硬编码进静态资源。
只暴露版本、进程工作目录与操作系统，不含密钥或环境变量。

后续里程碑追加：

```http
GET  /api/v1/skills          # M7
POST /api/v1/skills/refresh  # M7
GET  /api/v1/health          # M9
```

`/health` 计划返回数据库、Flyway、插件快照与活跃 run 数；M4 尚无该端点。

## 8. CLI（M9）

M4 只能通过 Java 进程启动服务：

```bash
java -jar pi-java-app/target/pi-java-app-0.1.0-SNAPSHOT.jar
```

picocli 命令在 M9 实现；以下为已冻结的规划契约。

### 8.1 命令总览

```text
pi chat   [--cwd PATH] [--model ID] [--session ID] [--profile demo|mysql]
pi run    "任务描述" [--cwd PATH] [--model ID] [--json] [--session ID]
pi sessions list|show|resume|fork [--cwd PATH]
pi plugins list|install|enable|disable
pi models list|add
pi tools list [--cwd PATH]
pi serve  [--port 8080] [--profile demo|mysql]
```

### 8.2 `pi chat`

交互式 REPL：

- 输入普通文本 → 创建/继续会话并发起 run；
- `/exit`、`/quit` 退出；
- `/abort` 取消当前 run；
- `/compact [说明]` 手动压缩；
- `/skill:name [args]` 强制加载 skill 正文并发送；
- `/tree` 显示当前分支节点摘要；
- `/model <id>` 切换模型（写 `MODEL_CHANGE` entry）；
- `/help` 命令帮助；
- Ctrl+C 第一次取消当前 run，第二次退出。

渲染：流式文本、工具调用卡片（名称/参数摘要/结果摘要）、审批提示（y/n/a，
a = 本会话后续允许）、token 使用量、上下文占用百分比。

### 8.3 `pi run`

非交互单次执行：

```bash
pi run "读取 pom.xml 并总结依赖" --cwd D:/code/demo --json
```

- 默认流式打印文本与工具摘要；
- `--json` 输出结构化结果：`runId/sessionId/stopReason/usage/turns/toolCalls/finalText`；
- 退出码：`0` 成功，`1` 运行失败，`2` 参数错误，`3` 审批被拒绝/超时，`4` 取消。

### 8.4 其他命令

```bash
pi sessions list --cwd D:/code/demo --limit 20
pi sessions show 01J...
pi sessions resume 01J...          # 进入 chat，继续该会话
pi sessions fork 01J... --entry 01J...
pi plugins install ./hello-plugin.jar
pi plugins enable hello-plugin
pi plugins disable hello-plugin
pi models list
pi models add --id deepseek-chat --provider OPENAI_COMPATIBLE \
  --base-url https://api.deepseek.com/v1 --api-key-env DEEPSEEK_API_KEY \
  --model-name deepseek-chat --context-window 65536
pi tools list --cwd D:/code/demo
```

CLI 与 Web 使用同一个数据库与同一套用例；CLI 不直接操作 Mapper。

## 9. Web UI

M4 的静态资源位于 `pi-java-app/src/main/resources/static/`，原生 HTML/CSS/JS，无构建步骤，
只依赖 `/api/v1`。当前页面为单栏布局：

```text
┌───────────────┬─────────────────────────────────────────┐
│ 会话侧栏       │ 消息流                                   │
│ + 新建会话     │ - 用户/助手气泡                           │
│ 会话列表       │ - 工具卡片（工具名、参数、输出、错误态）    │
│ 当前 run 状态  │ - 流式文本与光标                          │
│               │ - 停止按钮、token 统计                    │
└───────────────┴─────────────────────────────────────────┘
```

M4 已覆盖基础交互，M6 增加审批弹窗：

1. 新建会话（标题、工作目录、模型档案）→ 发送消息 → 流式增量 → 完成；
2. 工具卡片展示工具的参数与输出；
3. 停止运行，显示 ABORTED，保留已输出内容；
4. 会话切换与刷新后通过 `GET /sessions/{id}/run` 重连 SSE；
5. 输入 `/read README.md`、`/ls .`、`/write notes.txt 你好` 或普通问候即可在
   Fake 模型下体验；
6. 写工具触发 `approval.requested` 后弹出审批框：展示风险级别、工具名、脱敏参数、
   参数摘要与倒计时；批准/拒绝通过 `POST /approvals/{id}/decision` 提交；
   多个待办按到达顺序串行展示，刷新后通过 `GET /approvals?status=PENDING` 恢复；
7. `approval.resolved` 到达或决策提交成功后关闭弹窗，运行继续流式输出。

会话树与设置页分别在 M7–M8 接入。M5 的插件管理只有 REST API，
用 curl 或 App 内测试夹具操作，尚无 Web UI。

## 10. 版本与兼容

- API 版本在路径中；破坏性变更升 `/api/v2`；
- 响应只增字段不删字段；客户端忽略未知字段；
- 错误 code 稳定，message 可调整；
- CLI 参数只增不删；废弃参数先告警一个 minor 版本；
- OpenAPI 描述 v0.1 暂不生成，接口以本文与 DTO 测试为准。

## 11. 相关文档

- [运行时与事件](04-runtime-and-events.md)
- [插件 SDK](06-plugin-sdk.md)
