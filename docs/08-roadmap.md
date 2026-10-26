# 08 路线图

> 状态：M0–M15 已完成（M11 CLI 于 2026-10-24，M12 MCP 与 M13 Git 检查点
> 于 2026-10-24/25，M14 计划与自动验证于 2026-10-26，M15 工程化与 v0.2.0
> 于 2026-10-26）。详见 [M11 记录](milestones/M11.md)、[M12 记录](milestones/M12.md)、
> [M13 记录](milestones/M13.md)、[M14 记录](milestones/M14.md) 与
> [M15 记录](milestones/M15.md)；`v0.2.0` tag 与 GitHub Release 待用户确认。
> v0.2 已完成，`v0.3` 候选见第 6 节。

## 1. 交付策略

- 小步增量：每个里程碑只引入一组内聚能力，不同时铺开 Web、插件和子代理；
- 从领域到入口：先让领域规则可测，再接数据库，再接 LLM/工具，最后接 Web/CLI；
- 每个里程碑结束执行 `mvn -q verify`，更新对应文档与模块 README，单独 git commit；
- 不主动 push；发布 tag 需用户明确确认；
- 任何时刻主干可构建、可启动 demo（M4 起）。

## 2. 里程碑总览

| 里程碑 | 主题 | 交付 | 验收 | 提交信息 |
|---|---|---|---|---|
| **M0** | 骨架与文档 | 6+1 Maven 模块、Checkstyle/ArchUnit、Flyway 目录、docs/ADRs | `mvn -q -DskipTests package`；文档链接可解析 | `chore: bootstrap pi-java ddd skeleton and architecture docs` |
| **M1** | 会话领域与持久化 | `ChatSession`、`SessionEntry`、消息模型、投影、分支策略、V1、MyBatis 仓储 | 投影/分支/fork/配对/ULID 单测；H2 仓储集成测试 | `feat(session): tree session log and context projection` |
| **M2** | 模型接入 | `ModelProfile`、`LlmGateway`、Fake + OpenAI-compatible 适配器、V2、模型 API | WireMock 流式/重试/取消/错误；Fake 脚本测试 | `feat(model): model profiles and streaming llm gateway` |
| **M3** | 工具与沙箱（只读） | `ToolDescriptor/Catalog`、PathSandbox/RealPathResolver、read/ls/grep/glob、V3、工具目录 API | 路径穿越/符号链接/截断/超时；参数校验；策略默认值 | `feat(tool): read-only tools and path sandbox` |
| **M4** | Agent Loop + Web demo | Run 状态机、SSE、Web UI、Fake E2E、RUN_SUMMARY | 状态机、取消、SSE 回放、MockMvc、浏览器手工验证 | `feat(agent): agent loop, sse and read-only web demo` |
| **M5** | 插件 SDK | plugin-api、child-first ClassLoader、PluginRuntime、示例插件、V4、插件 API | 类加载隔离、注册回滚、卸载、契约纯度、示例独立编译 | `feat(plugin): zero-dependency plugin sdk and example` |
| **M6** | 审批 + 写工具 | ApprovalRequest、ToolPolicyRule、审批 API/SSE/UI、write/edit/bash、V5 | 审批并发/过期/取消、策略优先级、Windows 进程超时、脱敏 | `feat(governance): approval workflow and write tools` |
| **M7** | 压缩 + Skills | CompactionPlanner、TokenEstimator、SkillCatalog、system prompt、`/compact`、`/skill:` | pi 黄金样本、切点配对、摘要收敛、frontmatter、缓存失效 | `feat(context): compaction and skills` |
| **M8** | 子智能体 | SubagentPolicy、subagent 工具、SPAWN/FORK、子会话树、SSE/UI | 深度上限、工具子集、审批不继承、取消级联、结果 stopReason | `feat(agent): synchronous subagents` |
| **M9** | Web UI 完善与开源准备 | 设置中心、会话设置、上下文仪表、README/截图/许可证 | 浏览器走查、602 测试、公开仓库 | `feat(web): settings center, session settings and context meter` |
| **M10** | 界面重构 | 信号工作台设计系统、本地字体、外壳/对话/设置全面重做 | 1440×900 与 531×553 实测、截图更新、602 测试 | `feat(web): redesign interface with signal workbench design system` |
| **M11** | CLI + 打磨 + v0.1.0 | picocli 命令、CLI 流式与审批、MySQL smoke、用户手册、tag | CLI 冒烟、Fake E2E、本机 MySQL 启动、完整走查 | `feat(cli): picocli entry and v0.1.0 demo`，tag `v0.1.0` |
| **M12** | MCP 客户端 | stdio/HTTP 传输、工具动态发现、命名空间与审批策略 | 假 MCP 服务器端到端、超时/崩溃/重连 | `feat(mcp): mcp client and dynamic tools` |
| **M13** | Git 感知与检查点 | 启动快照、turn 前后 diff、撤销上一轮、提交建议 | 临时仓库回滚/冲突/未跟踪文件 | `feat(git): git-aware checkpoints and diff` |
| **M14** | 计划、待办与自动验证 | 计划条目、todo 工具、验证命令自动执行与回写 | 计划推进、验证失败重试、UI/CLI 展示 | `feat(agent): plans, todos and verification loop` |
| **M15** | 工程化与 v0.2.0 | GitHub Actions、Dockerfile、OpenAPI、发布说明与 tag | CI 全绿、容器启动、OpenAPI 校验 | `chore(release): ci, docker and v0.2.0` |
## 3. 各里程碑详情

### M0 骨架与文档

交付：

- 根 pom、7 个模块 pom（含示例插件）；
- `pi-java-app` 启动类、`application.yml`（demo/mysql）；
- Checkstyle、ArchUnit、Flyway 目录；
- docs/00–08、4 个 ADR、模块 README、M0 记录。

验收：

```bash
mvn -q verify
```

### M1 会话领域与持久化

状态：已完成（2026-10-21），详见 [M1 记录](milestones/M1.md)。

范围：

- `domain.shared`：ULID、错误码、异常、Guard；
- `domain.session`：聚合、entry、payload、消息 sealed、投影器、分支策略；
- V1 两表；MyBatis 仓储与 PO；
- 应用层：创建会话、追加消息、读取分支、fork/branch 用例；
- 不接 LLM、不接 Web。

验收：

- 同一 entry 序列的投影可重复；压缩语义正确；
- fork/分支不复制历史、seq 连续；
- H2 集成测试覆盖唯一键/CAS/级联。

实测：`mvn -q verify` 通过；65 个测试全绿；domain 行覆盖 89.4%、application 98.9%，
两个模块已启用 60% 行覆盖门槛。

### M2 模型接入

状态：已完成（2026-10-21），详见 [M2 记录](milestones/M2.md)。

范围：

- `domain.model`、`ModelProfile`、`ModelEndpoint`、`LlmGateway` 端口与 sealed delta；
- Fake provider（脚本化流、工具调用、错误、取消、回声兜底）；
- OpenAI-compatible provider（JDK HttpClient + 手写 SSE、分片工具调用、usage）；
- V2；模型配置用例与 API；
- 密钥只读环境变量，档案只存变量名。

验收：

- WireMock 覆盖流式、429/5xx、401、上下文溢出、断流、取消、畸形 JSON、缺密钥；
- 重试只发生在 adapter，且首个 delta 后不重试；
- 模型 API 拒绝疑似密钥值，实测创建/列表/启停/更新/404。

实测：147 个测试全绿；domain 行覆盖 85.4%、application 98.3%；
demo profile 启动后 `/api/v1/models` 全链路 curl 验证通过。

### M3 工具与沙箱（只读）

状态：已完成（2026-10-21），详见 [M3 记录](milestones/M3.md)。

范围：

- `domain.tool`：ToolDescriptor/Catalog/ArgumentGuard、ToolRegistry/ToolInvoker 端口；
- `domain.governance`：SandboxPolicy/PathSandbox、ToolPolicyRule/Repository、ToolAccessPolicy；
- infrastructure：RealPathResolver、read/ls/grep/glob、虚拟线程超时调用器；
- V3 策略表（MyBatis 仓储 + CAS）；
- 工具目录用例与 `GET /api/v1/tools`。

未包含（按计划后移）：

- `ToolExecutionPlanner` 与并行分批：M4 已实现，并入应用层 `ToolCallCoordinator`；
- 插件 hook 与工具拦截器：未在 M5 交付，后续里程碑按需引入；
- 审批、策略管理 API、write/edit/bash：M6。

验收结果：

- 路径穿越、符号链接、盘符切换被拒绝（Windows 下 2 个符号链接用例条件跳过）；
- 输出按 60k–80k 字符截断；超时返回 `TOOL_TIMEOUT`，中断返回 `CANCELLED`；
- 未知工具在 `ToolRegistry.require` 处失败，不进入策略解析；
- `GET /api/v1/tools` 在 demo 应用实测返回四个工具与 `ALLOW` 效果。

### M4 Agent Loop + 首个 Web demo

状态：已完成（2026-10-22），详见 [M4 记录](milestones/M4.md)。

范围（实际交付）：

- `domain.agent`：`AgentRun` 不可变快照、`RunBudget`、`RunStopReason`、`RunEvent/Type`；
- `application.agent`：`AgentLoopService`、`RunRuntime`、`ActiveRunRegistry`、
  `RunDispatcher`、`TurnPipeline`、`TurnExecutor`、`ToolCallCoordinator`、
  `SessionEntryAppender`、`RunEventHub`、`RunEventPublisher`；
- 工具管线：预检、策略、并行/串行、成对结果、预算与取消；
- 会话/运行 API 与 SSE：创建、列表、详情、分支 entry、活跃 run、消息流、abort、
  run 快照、事件重放；
- 原生 Web UI：新建会话、流式消息、工具卡片、停止、usage；
- `RUN_SUMMARY` entry 与惰性会话自愈；
- demo profile 无 API Key 可运行。

验收结果：

- `mvn -q verify` 通过；共 295 个测试（2 跳过），domain 86.7%、application 88.9%；
- 应用层 15 个 Agent Loop 单测覆盖文本/工具/并行/审批占位/取消/预算/自愈/启动清理；
- `RunSseIntegrationTest` 走真实 Spring MVC 异步链路，验证事件顺序与 `Last-Event-ID` 回放；
- 触发层 10 个新增 MockMvc 测试覆盖会话与运行控制器、状态码、错误映射；
- 浏览器手工走查 demo（新建 → `/read README.md` → 工具卡片 → 停止）。

未包含（按计划后移）：

- 审批等待与写工具：M6；
- 上下文压缩与 Skills：M7；
- 子代理：M8；
- CLI：M9。

### M5 插件 SDK

状态：已完成（2026-10-22），详见 [M5 记录](milestones/M5.md)。

范围（实际交付）：

- `pi-java-plugin-api`：`PiPlugin / PluginDescriptor / PluginContext / PluginTool /
  ToolSpec / PluginToolResult / PluginToolContext / CancellationToken / PluginLogger /
  Registration`，只依赖 JDK；
- `domain.extension`：`PluginId / ApiVersion / PluginPackage / PluginArtifact /
  PluginInstallation / PluginStatus`、`PluginRepository / PluginPackageInspector /
  PluginRuntime`、`PluginCompatibilityChecker`；
- infrastructure：`PluginJarInspector`、`PluginClassLoader`、`PluginApiClassLoader`、
  `PluginHost`、`RuntimeToolRegistry`、`RuntimeToolInvoker`、`PluginToolAdapter`；
- application：`PluginApplicationService`（安装、启停、查询、启动恢复）；
- trigger：`GET/POST /api/v1/plugins`、`POST /{id}/enable`、`POST /{id}/disable`；
- V4 `pi_plugin` 与 MyBatis 仓储；
- run 工具快照：`ToolRegistry.snapshot()`，`RunRuntime.reserve` 冻结；
- 示例插件 `examples/pi-plugin-example` 与安装/启用文档；
- Fake demo 新增 `/tool <name> <json>`，可端到端调用插件工具。

验收结果：

- 安装只落库；enable 后 `/api/v1/tools` 出现 `com.piagent.example.hello.greet`，
  disable 后消失；重复安装 409、未知插件 404、缺失 jar 422；
- 插件端到端：Fake 模型调用插件工具 → `tool.completed` → `echo:你好` →
  `run.completed`；停用后新调用得到“工具未注册”，run 仍完成；
- run 快照测试：run 开始后禁用插件，本次调用仍执行，第二个模型请求仍看到旧目录；
- 示例插件不依赖宿主模块，`plugin-api` ArchUnit 纯度检查通过；
- 全量 `mvn -q verify` 通过，368 个测试（2 跳过），domain 87.6%、application 89.7%。

未包含（按计划后移）：

- 插件命令、hook、工具拦截器、prompt section、消息/自定义 entry：M6–M7 或 v0.2；
- 插件 Web UI、热重载、卸载接口、配置读取：v0.2；
- 停用后旧类加载器保留到进程退出，属于已知限制。

### M6 审批 + 写工具

状态：已完成（2026-10-22），详见 [M6 记录](milestones/M6.md)。

范围（实际交付）：

- `domain.governance`：`ApprovalRequest` 状态机、`ApprovalStatus/Decision`、
  `ApprovalContext/Arguments`、`ApprovalRepository`、`ApprovalArgumentsSanitizer`；
- 应用层：`ApprovalApplicationService`（CAS 决策、APPROVAL entry 双写、afterCommit 发布）、
  `ApprovalGateService` + `ApprovalWaitRegistry`（阻塞等待、取消、TTL）、
  `ApprovalEventPublisher`、`ToolPolicyApplicationService`；
- 工具管线：`ToolCallPreflight` 拆出策略/预算预检；`ToolCallCoordinator` 在 `ASK`
  时创建审批、阻塞等待终态，批准后执行，拒绝/过期/取消写配对错误结果；
- 内置工具：`write`、`edit`、`bash`（Windows `cmd.exe` / Unix `/bin/sh`，
  超时终止进程树，输出截断）；
- HTTP：`GET /approvals`、`GET /approvals/{id}`、`POST /approvals/{id}/decision`、
  `GET/PUT /tool-policies`；错误映射新增审批 404/409；
- SSE：`approval.requested`（脱敏参数，无 digest）与 `approval.resolved`；
- Web UI：审批弹窗队列、风险徽标、脱敏参数、digest、倒计时、批准/拒绝、
  刷新后恢复待办；
- V5 `pi_approval_request`、MyBatis 仓储与 H2/MySQL 迁移；
- Fake demo 增加 `/write <path> <content>`。

验收结果：

- 批准：`/write` 触发 HIGH 审批，批准后文件落盘，SSE 顺序为
  `run.started → approval.requested → approval.resolved → tool.completed → run.completed`；
- 拒绝：工具返回 `isError=true`“用户拒绝执行工具 write”，文件不存在；
- 超时/取消：审批迁移为 EXPIRED/CANCELLED，工具得到配对错误结果；
- 策略优先级 WORKSPACE > USER > 风险默认；`write/edit` 默认 ASK，
  `bash` 默认 DENY；
- 脱敏与 digest：HTTP 详情含 64 位 digest，SSE 不含 digest；敏感键值替换为 `***`；
- H2 + Flyway + MyBatis 集成测试覆盖审批往返、CAS、唯一键与级联；
- `mvn verify` 全绿，Checkstyle/ArchUnit 通过。

未包含（按计划后移）：

- `approvalMode=SESSION/ALWAYS` 的“记住批准”语义：字段已持久化，执行器暂未消费；
- 策略 `timeoutMs` 尚未覆盖工具默认超时；
- 启动时遗留 PENDING 审批的批量 EXPIRED 清理；
- 审批 UI 暂不渲染历史 `APPROVAL` entry（审计仍在会话日志中）；
- 插件工具的高风险审批已走同一管线，但插件拦截器仍未实现。

### M7 压缩 + Skills

状态：已完成（2026-10-22），详见 [M7 记录](milestones/M7.md)。

范围（实际交付）：

- `domain.context`：`TokenEstimator`/`TokenEstimate`、`EntryTokenCounter`（统一 token 口径）、
  `ContextBudget`、`CompactionTriggerPolicy`/`CompactionDecision`、`CompactionPlanner`/`CompactionPlan`、
  `SummarySection`/`SummaryDraft`/`CompactionSummaryFormatter`、`CompactionOutcome`、
  `CompactionPromptBuilder`、`SkillDescriptor`/`SkillCatalog`/`SkillPromptFormatter`、`SkillFrontmatter`；
  端口 `SkillSource`、`WorkspacePromptSource`；
- 应用层 `application.context`：`ContextCompactionService`（测量→触发→规划→摘要→收敛→落库）、
  `CompactionSummarizer`、`ContextMeasurementFactory`、`CompactionApplicationService`（手动压缩）、
  `SkillApplicationService`；
- Agent Loop 集成：`RunCompactionStep`、`ContextCompactionState`、`TurnStreamer`（溢出重试）、
  `RunSteps`、`SkillInvocationResolver`、`PromptSections`；
- 基础设施：`FileSystemSkillSource`（多根、先到先得、TTL + mtime、正文缓存）、
  `AgentsDocumentSource`、`ContextConfiguration`/`CompactionConfiguration`/`AgentSettings`；
- HTTP：`POST /sessions/{id}/compact`、`GET /skills`、`GET /skills/{name}`、`GET /skills/{name}/content`；
- SSE：`compaction.started` / `compaction.completed`；
- 无新增表（摘要复用 `COMPACTION` entry）。

验收结果：

- 自动压缩在上下文超预算时触发；溢出后强制压缩并重试一次；
- 切点不落在 toolResult；split-turn 正确拆分；
- 摘要不收敛时写入 `prunedOnly=true`，不进入循环；
- 修正 M1 遗留的“3 字符 1 token”估算，中文会话压缩可正常触发；
- skill 缓存 mtime 失效、`?refresh=true` 生效；
- `mvn clean verify` 全绿，553 个测试（2 跳过），domain 89.2%、application 86.1%。

未包含（按计划后移）：

- `pi.agent.context.*` 配置项尚未接入预算推导（`ContextMeasurementFactory` 仍用默认比例）；
- 插件 `PromptSection` 契约（v0.2）；
- 图片/多模态摘要（v0.1 无多模态）。

### M8 子智能体

状态：已完成（2026-10-22），详见 [M8 记录](milestones/M8.md)。

范围（实际交付）：

- `domain.subagent`：`SubagentMode`、`SubagentPolicy`、`SubagentRequest`、`SubagentResult`、
  `SubagentToolset`（工具子集 + 深度裁剪，纯函数）；
- 应用层 `application.subagent`：`SubagentSessionFactory`、`SubagentExecutionService`、
  `SubagentService`、`SubagentToolInvoker`（`@Primary`，截获 `subagent`）、`SubagentRegistry`；
- `ToolInvocation` 增加 `originSessionId`，让需要父会话上下文的工具能拿到发起者；
- 循环依赖用 `ObjectProvider` 延迟解析，不开启全局 `allow-circular-references`；
- 配置：`pi.agent.max-subagent-depth`、`subagent-timeout-seconds`、`subagent-max-timeout-seconds`；
- 无新增表（子代理复用 `pi_session` 的 parent/root/depth/origin）。

验收结果：

- `subagent` 工具出现在目录中，默认 `ASK`；
- 深度达上限后子工具集不再包含 `subagent`；
- 白名单只收紧：父没有的工具不会因为白名单而出现；
- 子会话正确入树（parent/root/depth/origin），父会话保持 IDLE 且不被写入；
- 子代理失败/超时/异常都以 toolResult 回写，父循环不中断；
- `mvn clean verify` 全绿，573 个测试（2 跳过）。

未包含（按计划后移）：

- SSE `subagent.*` 事件与 UI 会话树展示；
- 异步/并行子代理、远程执行；
- 子会话沙箱进一步收紧（例如限制到子目录）；
- 审批的会话级“记住批准”复用。

### M9 Web UI 与开源准备

状态：已完成（2026-10-23），详见 [M9 记录](milestones/M9.md)。

范围（实际交付）：

- `SessionSettingsApplicationService` 与 `PATCH /sessions/{id}`、模型切换、归档；
- `GET /sessions/{id}/context` 只读上下文估算与手动压缩入口；
- 设置中心：模型/技能/插件/策略/系统五个标签；
- 聊天历史渲染 COMPACTION、BRANCH_SUMMARY、APPROVAL、MODEL_CHANGE；
- README、许可证、忽略规则、三张 1440×900 截图；
- `mvn -q verify` 602 个测试全绿（2 跳过）。

### M10 界面重构 · 信号工作台

状态：已完成（2026-10-23），详见 [M10 记录](milestones/M10.md)。

范围（实际交付）：

- `css/theme.css` 设计令牌、本地 Archivo / IBM Plex Mono 字体；
- 重做侧栏、顶栏、欢迎页、对话与工具轨迹、输入区和响应式布局；
- 重做审批/会话弹窗与设置中心五个标签页；
- 后续补强可读性（字号/对比度）、默认折叠的执行过程面板、斜杠命令提示与安全 Markdown 渲染；
- 更新 README 与六张截图，交互与安全边界不变。

验收结果：

- `mvn -q verify` 602 个测试全绿（2 个符号链接用例条件跳过），Checkstyle/ArchUnit 通过；
- 1440×900 与 531×553 完成新建会话、`/read`、审批批准/拒绝、设置五个标签走查；
- 控制台 0 错误，字体与静态资源全部 200。

### M11 CLI + 打磨 + v0.1.0

状态：已完成（2026-10-24），详见 [M11 记录](milestones/M11.md)。
CLI 契约见 [05 API 与 CLI](05-api-and-cli.md) §8。

范围（v0.1 CLI 子集）：

- 同一可执行 jar 双模式启动：无参数 / `pi serve` 启动 Web，其余首参进入 CLI；
- `pi run` 单次执行：流式文本、工具摘要、交互审批、`--json`、退出码；
- `pi chat` 对话框式交互界面：方框输入区、彩色流式、`Tab` 补全、Ctrl+C 取消，
  管道环境自动回退逐行 REPL（多轮对话、`/abort`、`/compact`、`/model`、`/tree`）；
- 只读命令：`pi sessions list|show`、`pi models list`、`pi tools list`；
- CLI 与 Web 共用 Spring 容器、数据库、Flyway 与全部应用用例，不新增持久化路径；
- 用户手册、demo 脚本、MySQL 本机 smoke、依赖清理与 `v0.1.0` tag（tag 需用户确认）。

验收：

- `pi run` 在 Fake demo 下完成只读工具与写工具审批（批准/拒绝）各一次，输出与退出码正确；
- 非交互环境默认拒绝审批，且错误信息说明如何显式放行；
- Web 启动方式与 M10 完全兼容，`mvn -q verify` 全绿；
- 验收清单（见 [00 愿景与范围](00-vision-and-scope.md) §7）逐项通过。

实测（2026-10-24）：643 个测试全绿；jar 冒烟覆盖帮助、只读命令、批准/拒绝/EOF、
`--json`、`chat`、错误退出码与 `serve --port 9099`；ConPTY 下验证交互界面的
横幅、Tab 补全、流式对话、审批落盘、`/exit` 与 Ctrl+D，Ctrl+C 因 pty 无法注入
改由信号单元测试覆盖。MySQL smoke 与 tag 待确认。

### M12 MCP 客户端

状态：已完成（2026-10-24），详见 [M12 记录](milestones/M12.md)。
配置与使用说明见 [10 MCP 客户端](10-mcp.md)。

范围（实际交付）：

- 配置驱动：`pi.mcp.servers`（`McpSettings`），无数据库迁移，默认空列表；
- stdio 传输：换行分隔 JSON-RPC、stderr 排水、Windows `.cmd/.bat` 包装、进程树终止；
- Streamable HTTP 传输：JSON/SSE 响应、`Mcp-Session-Id`、关闭时 DELETE；
- `initialize`（`2025-06-18`）+ `notifications/initialized` + 分页 `tools/list`；
- 命名空间 `<server>.<sanitized-tool>`，与内置/插件/其他服务器冲突检查；
- 风险映射：显式覆盖 → 服务器默认 → 名称启发式，随后进入既有审批管线；
- 所有 MCP 工具串行执行，超时、断线、远端错误转为 `isError` 工具结果；
- 传输明确关闭时下一次调用前重连；不自动重试已发出的工具调用；
- `McpStartupRunner` 启动连接，单服务器失败只告警。

验收结果：

- 假 stdio 服务器端到端：Spring + Fake 模型 `/tool echo.echo` 得到 `echo:你好` 与
  `tool.completed`，`/api/v1/tools` 可见带前缀工具；
- 真实子进程覆盖发现、调用、远端错误、超时与关闭；本地 HTTP 服务覆盖 JSON/SSE、
  会话头、DELETE 与 HTTP 500；
- 冲突工具不注册且不影响其他服务器；断线后重连一次，工具定义保持启动时快照；
- `mvn -q verify` 通过，671 个测试（2 跳过），Checkstyle/ArchUnit、覆盖率门槛
  与 Spring Boot repackage 全绿。

未包含（按计划后移）：

- MCP resources/prompts/sampling、服务端通知与目录热刷新；
- OAuth 授权、MCP 管理 API/UI、服务器配置持久化；
- 远程工具级别的沙箱隔离（仍由外部服务器自行负责）。

### M13 Git 感知与检查点

状态：已完成（2026-10-25），详见 [M13 记录](milestones/M13.md)。
配置、API 与限制见 [12 Git 检查点与撤销](12-git.md)。

范围（实际交付）：

- 配置：`pi.git.enabled / timeout-seconds / max-diff-chars`，默认启用；
- run 开始时用临时索引写 `GIT_CHECKPOINT`，包含 `baseLeafId`、树对象、HEAD 与分支；
- 每轮工具执行后与上一轮树对象比较，有变化写 `GIT_CHANGES` 并发布
  `workspace.changed`，包含文件列表、统一 diff、截断标记与提交建议；
- 未跟踪文件纳入快照（遵守 `.gitignore`），二进制文件可见但不展开 diff；
- `POST /sessions/{id}/undo`：冲突预检 → 还原本轮路径 → 回退会话分支；
  第一轮撤销同时清空 entry 日志，保证下一次追加是新的根；
- Web 改动面板与 CLI `/diff`、`/undo`，TUI 增加改动摘要行；
- 不创建分支、不移动 HEAD、不写用户索引、不自动提交；任何 Git 失败只降级。

验收结果：

- 临时仓库实测：新增/修改/删除/未跟踪文件均可撤销，`git log` 与分支保持不变；
- 用户在本轮后修改文件时撤销整体拒绝，冲突列表返回 200 且 `rewound=false`；
- 首轮撤销后可继续对话；非首轮撤销只影响该轮；
- 桌面与 390px 移动端走查通过，控制台无错误；
- `mvn -q verify -Dspring-boot.repackage.skip=true` 通过，715 个测试（2 跳过），
  Checkstyle/ArchUnit 与覆盖率门槛全绿；8080 实例占用 jar，未执行 repackage。

未包含（按计划后移）：

- 任意检查点回退、单轮中间状态、提交执行与推送；
- 子模块内部还原、重命名语义、空目录清理；
- 大仓库快照的性能优化与对象保活（`git gc` 后旧树可能失效）。

### M14 计划、待办与自动验证

状态：已完成（2026-10-26），详见 [M14 记录](milestones/M14.md)。
计划、验证配置与展示见 [13 计划、待办与自动验证](13-plans-and-verification.md)。

范围（实际交付）：

- `domain.plan`：`Plan`、`PlanItem`、`PlanItemStatus`，最多 20 条、最多一条
  `in_progress`、内容 ≤200 字；
- 日志：`PLAN` / `VERIFICATION` entry 与 payload，最新 `PLAN` 即当前计划，
  两者都不进入模型上下文；无数据库迁移；
- `todo` 工具：整表替换、LOW 风险、串行、免审批，返回摘要与 details；
- `GET /sessions/{id}/plan`：只读返回计划与最近一次验证；
- 自动验证：`pi.verify.command`（空 = 关闭）、超时与输出上限；
  run 改过 `write/edit/bash` 且模型准备收尾时执行一次；
- 失败回写：写 `VERIFICATION` entry 后追加中文用户消息，内容含命令、退出码/超时
  与截断输出；每个 run 最多回写一次；
- SSE：`plan.updated`、`verification.completed`；
- Web：待办面板（进度、当前项、展开列表、验证输出）与历史折叠卡片；
- CLI：`[待办]` / `[验证]` 摘要、TUI 短行、`/plan` 命令；
- 循环依赖集中为 `AgentLoopDependencies`，由基础设施配置装配。

验收结果：

- 隔离副本中 `mvn -q verify`：768 个测试全绿（2 个符号链接用例条件跳过），
  Checkstyle/ArchUnit/覆盖率与 repackage 全绿；8080 实例未受影响；
- 8082 + Fake demo 实测：待办面板 33%/1/3、写工具审批、验证失败徽标与历史卡片，
  Toast 提示已回写；桌面 1440×900 与移动端 390×844 控制台 0 错误；
- CLI 实测：`[待办] 1/2 完成`、`[审批] write APPROVED`、
  `[验证] 未通过 · cmd /c exit 1 · 78ms`，失败输出回到模型后继续一轮。

未包含（按计划后移）：

- 计划增量更新与人工编辑入口；
- 多轮验证、验证矩阵与并行项目；
- 验证结果单独表、跨会话统计与 CI 集成。

### M15 工程化与 v0.2.0

状态：已完成（2026-10-26），详见 [M15 记录](milestones/M15.md) 与
[v0.2.0 发布说明](releases/v0.2.0.md)。

范围：

- GitHub Actions 三作业：`mvn verify`、Docker 构建与容器 smoke、MySQL profile smoke；
- 多阶段 `Dockerfile`：非 root、H2 数据卷与工作区分离、内置健康检查；
- `docker-compose.yml`（H2）与 `docker-compose.mysql.yml`（MySQL 叠加）；
- springdoc OpenAPI：`/v3/api-docs`、`/swagger-ui.html` 与契约测试；
- 版本号统一升级为 `0.2.0`，更新启动脚本、文档与发布说明。

关键设计：

- **容器内监听与宿主侧安全默认值分离**：镜像内 `PI_SERVER_ADDRESS=0.0.0.0`，
  Compose 端口仍只映射 `127.0.0.1`；
- **数据与工作区分卷**：H2 落 `/app/data` 命名卷，用户仓库挂 `/workspace`，
  避免数据库文件写进用户项目；
- **契约随代码生成**：OpenAPI 由 Controller 扫描生成，测试锁定版本与核心路径，
  不再手写一份易过期的接口清单；
- **CI 与本地一致**：CI 直接执行 `mvn verify`，不降低静态检查或跳过测试；
  MySQL 作业通过 HTTP 建会话读回，验证 Flyway、方言与仓储链路。

验收结果：

- `mvn verify`：770 个测试通过（2 个符号链接用例条件跳过）；
- `OpenApiDocumentIntegrationTest`：文档端点、版本 `0.2.0`、8 个核心路径与
  Swagger UI 静态页全部通过；
- CI 工作流与 compose 文件通过 YAML 解析校验；
- 本机未安装 Docker，镜像构建与容器启动由 CI 的 `docker` 作业校验；
  本地用 `PI_DATA_DIR`、`PI_SERVER_ADDRESS` 与独立工作目录运行 jar 验证容器等价参数。

未包含（按计划后移）：

- 镜像仓库发布、多架构构建、SBOM 与镜像签名；
- 自动创建 tag 与 GitHub Release（需用户确认）；
- Testcontainers、Kubernetes/Helm 与横向扩展。

## 4. 关键依赖

```text
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─► M5 ─► M6 ─► M7 ─► M8 ─► M9 ─► M10 ─► M11 ─► M12 ─► M13 ─► M14 ─► M15
                  │           │
                  └─ 只读工具  └─ 插件必须先有稳定工具目录
```

- M2 与 M3 可在 M1 后并行实现，但 M4 依赖两者；
- M5 依赖 M3 的工具目录抽象与 M4 的 run 生命周期；
- M6 依赖 M3 的沙箱与 M5 的插件工具生命周期；插件拦截器不是 M5 的交付物，
  如需要应在 M6 以新契约引入；
- M7 依赖 M4 的上下文组装；
- M8 依赖 M4 的 run 生命周期与 M6 的审批；
- M12 依赖 M3 的工具目录、M6 的审批与 M5 建立的运行时来源模型；
- M13 依赖 M4 的 run 生命周期与 M6 的审批，在会话日志上增加工作区检查点；
  M14 复用检查点判断“改完是否验证”，并增加计划与待办。

## 5. 发布与版本

- 仓库版本 `0.2.0`，v0.2.0 待创建 tag；
- tag 只在用户确认后创建；不自动 push；
- 每次里程碑提交使用 Conventional Commits；
- 发布说明包含：新增能力、已知限制、启动方式、验收结果。

## 6. v0.2 路线（2026-10-23 确认）

对照成熟编码代理的差距，v0.2 按以下顺序交付；每项独立成里程碑，不合并发布：

1. **M12 MCP 客户端**（已完成 2026-10-24）：stdio 与 Streamable HTTP 传输、
   `tools/list` 动态发现、服务名命名空间、工具风险映射、断线与超时处理。
   这是补齐外部能力面的最高杠杆项。
2. **M13 Git 感知与检查点**（已完成 2026-10-25）：run 前快照、turn 前后 diff、
   撤销上一轮、未跟踪文件处理与提交建议；变更展示同时进入 Web 与 CLI。
3. **M14 计划、待办与自动验证**（已完成 2026-10-26）：计划/待办条目与工具、
   验证命令自动执行、失败后带上下文重试；避免代理“改完不验”。
4. **M15 工程化与 v0.2.0**（已完成 2026-10-26）：GitHub Actions、Docker 镜像、
   OpenAPI 生成、发布说明与版本号；tag 与 GitHub Release 待用户确认。

明确推迟到 v0.3：

- Anthropic 原生 provider（当前 OpenAI-compatible 已覆盖主流网关）；
- 图片/多模态输入与 TUI；
- 并行子代理与远程执行；
- 仓库地图/LSP 索引；
- 多实例部署、Redis 事件总线、配额与成本中心。

## 7. 风险与应对

| 风险 | 影响 | 应对 |
|---|---|---|
| entry envelope 反复改 | 数据迁移困难 | M0 冻结 envelope；payload 只加字段；黄金样本 round-trip |
| 插件契约泄漏内部类型 | 第三方无法独立升级 | plugin-api 零依赖；ArchUnit；示例独立编译 |
| 审批/取消/SSE 竞态 | 卡死或重复写 | 显式状态机；单 session 单 run；CAS；取消令牌；恢复流程 |
| Windows 路径/进程差异 | 工具行为不一致 | RealPathResolver/ProcessRunner 抽象；条件测试；本机优先验证 |
| H2/MySQL 方言差异 | 生产迁移失败 | Flyway 分目录；CI H2 + 本机 MySQL smoke |
| token 估算偏差 | 压缩不触发/不收敛 | 字符启发式 + usage 锚点；剪枝回退 |
| 范围膨胀 | 无法交付 v0.1 | 非目标清单；里程碑门禁；新想法进 v0.2 |

## 8. 完成定义（Definition of Done）

一个里程碑只有同时满足以下条件才算完成：

1. 代码实现并通过 `mvn -q verify`；
2. 新行为有单元/集成测试，失败路径有覆盖；
3. 相关文档、DDL、API 契约已更新；
4. demo 或 CLI 可实际运行验证；
5. 没有硬编码密钥与越界依赖；
6. 单独 git commit，信息符合规范；
7. 已知限制写入文档，不隐藏在代码注释里。
