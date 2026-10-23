# 08 路线图

> 状态：M0–M10 已完成（M10 界面重构于 2026-10-23），下一步 M11 CLI + 打磨 + v0.1.0。
> 里程碑顺序不可跳跃；每个里程碑以“可运行 + 可测试 + 有文档 + 有提交”为完成标准。

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
| **M11** | CLI + 打磨 + v0.1.0 | picocli 命令、MySQL smoke、demo 脚本、用户手册、tag | CLI 冒烟、Fake E2E、本机 MySQL 启动、完整走查 | `feat(cli): picocli entry and v0.1.0 demo`，tag `v0.1.0` |

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

### M11 CLI + 打磨 + v0.1.0（下一里程碑）

范围：

- picocli 全部命令；
- 用户手册、demo 脚本、截图；
- MySQL 本机 smoke；
- 依赖清理、覆盖率复核、tag。

验收：

- 验收清单（见 [00 愿景与范围](00-vision-and-scope.md) §7）逐项通过；
- `v0.1.0` tag 指向最终提交。

## 4. 关键依赖

```text
M0 ─► M1 ─► M2 ─► M3 ─► M4 ─► M5 ─► M6 ─► M7 ─► M8 ─► M9 ─► M10 ─► M11
                  │           │
                  └─ 只读工具  └─ 插件必须先有稳定工具目录
```

- M2 与 M3 可在 M1 后并行实现，但 M4 依赖两者；
- M5 依赖 M3 的工具目录抽象与 M4 的 run 生命周期；
- M6 依赖 M3 的沙箱与 M5 的插件工具生命周期；插件拦截器不是 M5 的交付物，
  如需要应在 M6 以新契约引入；
- M7 依赖 M4 的上下文组装；
- M8 依赖 M4 的 run 生命周期与 M6 的审批。

## 5. 发布与版本

- 仓库版本 `0.1.0-SNAPSHOT`，M11 发布 `0.1.0`；
- tag 只在用户确认后创建；不自动 push；
- 每次里程碑提交使用 Conventional Commits；
- 发布说明包含：新增能力、已知限制、启动方式、验收结果。

## 6. v0.2 候选

- MCP 客户端与工具动态发现；
- Anthropic 原生 provider；
- 异步/并行子代理与远程执行；
- 插件热重载、签名与权限声明；
- 多实例部署、Redis 事件总线与缓存；
- 图片/多模态输入、TUI；
- OpenAPI 生成与更完整的契约测试；
- 会话归档、配额与成本统计。

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
