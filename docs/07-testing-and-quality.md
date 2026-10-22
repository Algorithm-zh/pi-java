# 07 测试与质量

> 状态：M5 更新。每个里程碑必须通过本文定义的统一门禁，才能提交与进入下一阶段。

## 1. 质量目标

| 目标 | 度量 | 门禁 |
|---|---|---|
| 领域规则可离线验证 | domain 单测，无 Spring/DB | `mvn verify` |
| 模块边界不腐化 | ArchUnit 规则 | 全绿 |
| 核心链路可回归 | Fake LLM + H2 端到端 | 全绿 |
| 真实协议不靠猜 | WireMock 模拟 OpenAI-compatible SSE | 全绿 |
| 代码可维护 | 文件 ≤ 300 行、方法 ≤ 50 行、参数 ≤ 6 | Checkstyle |
| 文档与代码同步 | 每个里程碑更新 docs 与 README | 人工检查 |
| 覆盖率不倒退 | domain/application 行覆盖 ≥ 60% 起步 | JaCoCo |

## 2. 测试金字塔

```text
                 ┌──────────────────────┐
                 │ 浏览器手工走查 / E2E  │  少量，验收用
                 ├──────────────────────┤
                 │ 应用层集成测试（H2）  │  每个用例一条主路径
                 ├──────────────────────┤
                 │ 适配器测试（WireMock）│  流式、重试、取消
                 ├──────────────────────┤
                 │ 领域单元测试          │  大量、快速、无 IO
                 └──────────────────────┘
```

原则：

- 领域逻辑不靠集成测试兜底；不变量必须在 domain 测试中覆盖；
- 数据库相关规则（唯一键、CAS、级联）用 H2 集成测试验证；
- HTTP/SSE 解析用 WireMock，不访问真实模型；
- 浏览器手工走查只验证交互，不替代自动化测试；
- 测试失败时先复现再修，不允许通过降低断言或跳过测试“修复”。

## 3. 各模块测试策略

| 模块 | 测试类型 | 关键内容 |
|---|---|---|
| domain | JUnit 5 + AssertJ | ULID 排序/解析、entry 校验、投影、分支策略、压缩切点、消息配对、路径判定、审批状态机、子代理权限 |
| plugin-api | 单元测试 + ArchUnit | 标识/工具定义校验；只依赖 JDK；示例插件独立编译 |
| application | JUnit + Mockito | 用例编排、状态机、取消传播、重试边界、事件顺序、run 工具快照 |
| infrastructure | H2 集成、WireMock、临时目录、动态 jar | MyBatis 映射、Flyway、LLM SSE 解析、工具路径/超时、插件加载与隔离 |
| trigger | MockMvc + 单元测试 | DTO 校验、错误映射、SSE 格式、插件端点状态码 |
| app | `@SpringBootTest` | 装配、Flyway、ArchUnit、插件安装/启停/端到端、SSE 链路 |

## 4. 领域测试清单

### 4.1 会话

- 空会话 → 追加首条 entry：`parentId=null`、`seq=1`、leaf 指向新 entry；
- 追加第二条：`parentId=leaf`、`seq=2`；
- 分支：回到旧节点后追加，旧分支保留、leaf 移动、seq 继续递增；
- fork：root/parent/depth/origin 正确；不允许 fork 到别的会话；
- 投影：分支只包含祖先链；压缩后只保留摘要 + 尾部；
- 工具配对：toolResult 无 toolCall 时报错；toolCall 缺结果时标记悬空；
- 切点：压缩切点落在 toolResult 时向前回退到合法节点。

### 4.2 工具与治理

M3 已覆盖：

- 工具名/描述/schema/超时约束；目录拒绝重名；未知工具 `TOOL_NOT_FOUND`；
- 参数守卫：未知参数、必填缺失、类型错误、长度/范围越界均返回 `TOOL_INVALID_ARGUMENT`；
- 策略：风险默认值（LOW ALLOW / MEDIUM/HIGH ASK / CRITICAL DENY）、
  WORKSPACE > USER > 默认、`WorkspaceKey` 规范化与 `GLOBAL` 键；
- 路径：空白路径、相对/绝对路径、`..` 逃逸、NUL、盘符切换、符号链接逃逸、
  不存在路径、新建文件的最近存在祖先；
- `read`：偏移/行数、二进制拒绝、单行与总输出截断、越界区间提示；
- `ls/grep/glob`：深度、条目数、匹配数、二进制/大文件跳过、隐藏与构建目录跳过、
  glob 根目录文件匹配、绝对 pattern 拒绝；
- 超时生成 `TOOL_TIMEOUT`、中断生成 `CANCELLED`、handler 异常包装为 `isError=true`；
- V3 仓储：往返、工作区+全局查询、CAS 冲突、唯一键冲突。

M6 已覆盖：

- 写工具默认 `ASK`、`edit` 默认 `ASK`、`bash` 默认 `CRITICAL`（默认 DENY）；
- 审批状态机：PENDING→APPROVED/DENIED/EXPIRED/CANCELLED，终态不可逆；
- `ApprovalArgumentsSanitizer`：canonical JSON 对键顺序稳定、数字规范化、
  敏感键脱敏、null 展示、SHA-256 形状；
- 写工具：新建/覆盖、父目录创建、目录目标拒绝、越界路径拒绝、参数守卫；
- 编辑工具：唯一匹配、多匹配拒绝、`replaceAll`、二进制/大文件拒绝、未找到文本；
- shell 工具：stdout/stderr 合并、输出截断、超时与中断终止整棵进程树、工作区 cwd；
- 审批门面：批准唤醒、拒绝唤醒、取消、TTL 过期；
- 工具管线：ASK 阻塞后批准执行、拒绝不执行、审批失败得到配对错误结果；
- HTTP：审批列表/详情/决策、非法 decision/status、409/404 映射、策略 GET/PUT。

已实现测试（M6）：`ApprovalRequestTest` 10、`ApprovalArgumentsSanitizerTest` 5、
`WriteToolHandlerTest` 7、`EditToolHandlerTest` 9、`BashToolHandlerTest` 8、
`AgentLoopApprovalTest` 4、`ApprovalGateServiceTest` 4、
`ToolPolicyApplicationServiceTest` 6、`ApprovalControllerTest` 7、
`ToolPolicyControllerTest` 7、`ApprovalPersistenceIntegrationTest` 6、
`ApprovalEndToEndIntegrationTest` 2。

### 4.3 运行时

M4 已覆盖（`AgentLoopServiceTest` 15 个用例 + `AgentLoopIntegrationTest` 2 个链路）：

- 同一 session 并发发起两个 run：第二个返回 `RUN_ALREADY_ACTIVE`（单测）
  或全局满额返回 `AGENT_BUSY`（并发许可测试）；
- 文本 run 单轮完成；工具 run 两轮完成且 toolCall/toolResult 成对；
- 并行工具按声明顺序落库；混入串行工具时整批串行；
- `ASK` 策略在 M4 转为错误结果且不执行工具；工作区规则可 `DENY`；
- 未注册工具得到配对错误结果，run 继续；
- 取消：LLM 流收到取消，assistant 保留部分文本并以 ABORTED 落库；
- 模型失败：不写 assistant 消息，写 RUN_SUMMARY(ERROR)；
- maxTurns 与工具预算耗尽写 `MAX_STEPS`；
- 启动失败清理：模型缺失回滚预留、会话恢复 IDLE；
- 悬空 RUNNING 会话在下次启动前自愈为 IDLE；
- run 结束必写且只写一条 RUN_SUMMARY。

M6 已补充审批等待/超时/取消与 SSE 事件；M7 已补充压缩切点、收敛判定、
token 口径回归与技能扫描/缓存。

### 4.4 压缩与 Skills

- 触发阈值：`tokens > window - reserve`；
- 保留最近 `keepRecentTokens`，不切 toolResult；
- split-turn：单个超长 turn 生成前缀摘要；
- 摘要不收敛时降级为剪枝，run 仍能继续；
- Skill frontmatter 解析：name/description 必填，非法 name 警告但加载；
- 目录 mtime 改变后缓存失效；`/skill:name` 注入正文。

### 4.5 插件（M5）

- `PluginId` / `ApiVersion` / `ToolSpec` 的字符集、长度与版本规范化；
- 聚合状态迁移：INSTALLED/DISABLED/FAILED → ACTIVE、ACTIVE → DISABLED、失败 → FAILED；
- jar 检查：缺失/非文件/超大、清单缺字段、入口类不存在、打包 plugin-api 类、
  API 主版本不为 1、安装后 jar 被替换；
- 加载回滚：`start` 抛异常、工具 schema 非法、工具重名时，实时目录不残留；
- 停用：工具立即从目录移除，旧 run 的快照与工具句柄仍可用；
- 工具名：完整名等于 `pluginId.toolName`，与内置工具冲突时整次加载失败；
- 已实现测试：plugin-api 3 个、infrastructure 135 个（含 2 个符号链接跳过）、
  app 插件 HTTP 3 个 + 端到端 1 个、示例插件 4 个。

### 4.6 子代理

- 深度上限：达到后子工具目录不含 `subagent`；
- 工具子集：请求白名单不能超出父快照；
- 审批不继承：父已允许的 write 在子会话仍需审批；
- 取消级联：父取消 → 子 ABORTED；
- 结果：`isError = stopReason != STOP`，带 childSessionId/usage。

## 5. LLM 适配器测试

### 5.1 FakeLlmProvider

确定性脚本由测试或 demo 提供，每个回合描述增量、最终响应或失败位置：

```java
FakeLlmProvider fake = new FakeLlmProvider(
        FakeTurn.text("先读取文件"),
        FakeTurn.toolCall("read", Map.of("path", "pom.xml")),
        FakeTurn.text("依赖如下...").withUsage(new Usage(100, 20, 0, 0, 120)));
```

要求：

- 支持文本、思考、工具调用、指定位置失败与延迟；`DoneDelta` 由流实现补发；
- 失败必须发生在全部脚本 delta 之后，避免不可达增量；
- 脚本耗尽后退化为回声（回显最后一条用户消息），保证 demo 永远有输出；
- 所有离线 E2E 默认使用 Fake，不读取 API Key。

已覆盖：文本与 usage、分片工具调用、部分输出后失败、首个 delta 前失败、
慢流取消、关闭、回声兜底。

### 5.2 OpenAI-compatible

WireMock 覆盖（10 个端到端用例）：

| 场景 | 断言 |
|---|---|
| 正常 SSE | 逐块文本、`[DONE]`、usage（含缓存命中）、Authorization 头 |
| 分片 toolCall | 按 index 合并参数，最终 `ToolCallBlock` 与 `TOOL_USE` |
| 429 后成功 | 重试 2 次内恢复，共 2 次请求，事件不重复 |
| 500 持续 | 3 次请求后抛 `SERVER_ERROR`，可重试标记正确 |
| 401 | 不重试，抛 `AUTHENTICATION` |
| 400 上下文溢出 | 映射 `CONTEXT_OVERFLOW`，不重试 |
| 畸形 SSE | `MALFORMED_RESPONSE`，不重试 |
| 首个 delta 后畸形 | 已输出文本保留，`response()` 为空，不重试 |
| 缺失环境变量 | `MISSING_API_KEY`，0 次网络请求 |
| 首次读取前取消 | `hasNext()` 立即 false，0 次网络请求 |

另有 SSE 解析器单元测试覆盖 thinking 顺序、usage 缺省补齐、缓存字段取较大值、
多工具调用按 index 排序、空流、`tool_calls` 与内容不一致、缺少工具 id/name、
参数不是 JSON 对象等协议边界。

不引入 Spring AI / LangChain4j；HTTP 使用 JDK `HttpClient`，SSE 解析手写在 adapter 内。
真实 DeepSeek 连通性不属于离线测试，留到 M9 验收（需环境变量）执行。

## 6. 数据库集成测试

- H2 内存库，`MODE=MySQL`；每次测试类前执行 Flyway，测试间清理业务表；
- 不使用 `schema.sql`；迁移脚本是唯一建表方式；
- 覆盖：
  - `(session_id, seq)` 唯一冲突；
  - `version` CAS 失败回滚；
  - session 删除级联 entry/approval；
  - parent_session `SET NULL`；
  - 工具策略 `(scope, workspace_key, tool_name)` 唯一键、工作区+全局查询、CAS；
  - 插件记录往返、状态更新、重复 id 冲突（M5）；
  - 审批唯一键 `(run_id, tool_call_id)`、参数 JSON 往返、按会话/状态查询、
    pending CAS、`expireAllPending`、级联删除（M6）；
  - 时间精度毫秒、UTF-8 中文往返；
- MySQL 方言不靠 H2 完全替代：M9 增加本机 MySQL smoke（建库 → Flyway → 启动 → 一条会话）。

## 7. 架构与静态检查

### 7.1 ArchUnit

`ModuleBoundaryTest` 强制：

- domain 只依赖 JDK；
- application 不依赖 Web/ORM/Jackson/plugin-api/infrastructure/trigger；
- plugin-api 只依赖 JDK；
- trigger 不依赖 infrastructure/MyBatis；
- infrastructure 不依赖 trigger。

新增模块或包时先补规则，再写代码。

### 7.2 Checkstyle

主代码（不含测试）在 `verify` 阶段检查：

- 文件 ≤ 300 行；
- 方法 ≤ 50 行；
- 参数 ≤ 6；
- 一个顶层类型；
- public 类型必须有 Javadoc；
- 禁止 tab、星号导入、未使用导入；
- 行宽 ≤ 140。

局部豁免必须写 `@SuppressWarnings("checkstyle:XXX")` 并在提交说明中解释。

### 7.3 JaCoCo

- M1 起对 `pi-java-domain`、`pi-java-application` 设置 60% 行覆盖门槛；
- 后续里程碑不得降低已达成覆盖率；
- 生成 HTML 报告到 `target/site/jacoco`；
- 排除纯 record/DTO 需谨慎：优先补行为测试，不靠排除刷数字。

M7 实测（2026-10-22，`mvn clean verify`）：

| 模块 | 行覆盖 | 测试数 | 门槛 |
|---|---|---|---|
| `pi-java-domain` | 89.2%（1588/1780） | 174 | ≥ 60% |
| `pi-java-application` | 86.1%（1536/1783） | 101 | ≥ 60% |
| `pi-java-infrastructure` | 64.7%（1718/2655） | 176（2 跳过） | 无门槛 |
| `pi-java-trigger` | 47.5%（199/419） | 42 | 无门槛 |
| `pi-java-app` | —（集成测试为主） | 53 | 无门槛 |
| `pi-java-plugin-api` | 73.1%（49/67） | 3 | 无门槛 |
| `examples/pi-plugin-example` | 100%（13/13） | 4 | 无门槛 |

当前共 553 个测试全绿（2 个符号链接用例在 Windows 无权限环境条件跳过）。
M7 新增约 105 个测试，domain 因为新增的纯函数上下文组件（估算器、规划器、摘要格式化、
frontmatter 解析）覆盖了大部分新增行，行覆盖反而从 87.3% 升到 89.2%。

M7 特别补充的回归测试：

- `SmallWindowCompactionTest`：锁定“小窗口下预算仍合法且能切出切点”；
- `CompactionPlannerBoundaryTest`：锁定“保留窗口覆盖全量时不压缩”；
- `EntryTokenCounterTest`：锁定落库估算与投影汇总同源（对应 M7 修复的 token 口径缺陷）。

infrastructure 覆盖率较低是因为 `BuiltInToolInvoker` 的关闭/拒绝分支、
`PluginHost` 的若干异常回滚路径、进程/文件 IO 异常与 Windows 专用分支不易稳定触发；
这些路径由行为测试和手工 smoke 兜底，不以排除代码的方式抬高数字。

application 的用例编排由内存端口单测覆盖，真实 SQL 行为由 app 模块的 H2 集成测试覆盖，
避免为了覆盖率把基础设施依赖倒灌进应用层。

## 7.4 测试环境

- `pi-java-app/src/test/resources/application-test.yml` 提供内存 H2（MySQL 模式）；
- 集成测试使用 `@ActiveProfiles("test")`，Flyway 扫描 `db/migration/h2`；
- 测试类之间用 SQL 清空 `pi_session_entry` 与 `pi_session`，不依赖执行顺序；
- 数据库连接串带 `DATABASE_TO_LOWER=TRUE` 与 `CASE_INSENSITIVE_IDENTIFIERS=TRUE`，与 demo 环境一致。

## 8. 端到端测试

### 8.1 demo E2E（M4 起）

已自动化（`RunSseIntegrationTest`，真实 Spring MVC + H2 + Fake + 读工具）：

1. 创建会话；
2. POST `/messages/stream` 并等待异步 emitter 完成；
3. 断言 `run.started → turn.started → message.* → tool.started/completed →
   message.completed → run.completed` 的顺序与 `id=runId:seq`；
4. 用 `Last-Event-ID: runId:2` 重连，断言只回放 `seq>2` 的事件且连接自动关闭。

插件 E2E（M5，`PluginEndToEndIntegrationTest`）：

1. 用测试类路径中的字节码动态生成插件 jar；
2. `POST /plugins` 安装 → `POST /plugins/{id}/enable`；
3. 创建会话 → `/tool app.test.sample.echo {"text":"你好"}`；
4. 断言 SSE 出现 `tool.completed`、插件工具名、`echo:你好` 与 `run.completed`；
5. 停用插件 → 再次调用得到“工具未注册”，run 仍完成。

浏览器手工走查（M4 验收）：

1. 启动 demo profile（H2 + Fake）；
2. 新建会话 → 发送 `/read README.md` → 观察流式文本与工具卡片；
3. 停止一次运行，确认界面显示 ABORTED 且保留部分输出；
4. 刷新页面，确认会话列表与历史 entry 可恢复。

插件安装/启停在 M5 通过 curl 走查（无 Web UI）；M6 增加审批写文件走查：

1. 新建会话 → 发送 `/write approval-demo.txt 你好`；
2. 确认弹窗展示 `write / HIGH`、脱敏参数、digest 与倒计时；
3. 批准 → 确认文件写入与工具卡片成功态；
4. 再次发送并拒绝 → 确认文件不存在、工具卡片错误态；
5. 待审批时刷新页面 → 确认弹窗恢复且倒计时继续。

会话树/分支查看在 M7–M8 加入走查清单。

### 8.2 黄金样本（M7）

从 pi 的真实 JSONL 会话中提取脱敏样本，验证：

- 树形 entry 解析；
- 压缩后投影结果；
- 工具调用/结果配对；
- 未知字段向前兼容。

黄金样本放 `pi-java-domain/src/test/resources/golden/`，只读，不修改原始文件。

## 9. Windows 与跨平台

- 路径测试用 `@EnabledOnOs` 区分 Windows/Unix；
- Windows 盘符、反斜杠、大小写不敏感、符号链接权限需单独用例；
- `bash` 工具在 Windows 使用 `cmd.exe /c` 或配置的 shell；测试覆盖超时与子进程树清理；
- 文件锁、临时目录清理使用 `@TempDir`；
- CI 若只跑 Linux，Windows 行为由本机手工 smoke 兜底，文档明确。

## 10. 提交前检查清单

```bash
mvn -q verify                 # 单测 + 集成 + 静态检查
git status                    # 确认没有 target/.env/密钥
git diff --stat               # 确认没有巨文件
```

- [ ] 新行为有测试，且测试在无网络环境可跑；
- [ ] 没有硬编码密钥、token、个人路径；
- [ ] 文档与 API/DDL 同步；
- [ ] 模块依赖方向未破坏；
- [ ] 失败路径（超时、取消、拒绝）有覆盖；
- [ ] 提交信息符合 Conventional Commits。

## 11. 已知取舍

- v0.1 不做 Testcontainers：本机 Docker 不可用，MySQL 用本机 smoke 补充；
- 不做变异测试、契约测试平台、性能基准；
- SSE 前端自动化暂用 MockMvc + 手工浏览器走查，M9 再评估 Playwright；
- 覆盖率门槛只覆盖 domain/application，基础设施靠行为测试而非数字。

## 12. 相关文档

- [整体架构](01-architecture.md)
- [路线图](08-roadmap.md)
