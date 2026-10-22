# pi-java-application

应用层。用例编排、Agent Loop、事务边界、读模型。

## 依赖

- `pi-java-domain`；
- Spring `spring-context` / `spring-tx`（仅注解与事务抽象）。

**禁止**：MyBatis、Jackson、Web、插件 API、infrastructure、trigger。

## 职责

- 一个用例一个类，输入使用自己的 `Command` / `Query` record，输出使用 `ReadModel`；
- `AgentLoopService` 只推进 run 状态机与终止判定，流式读取、工具执行、上下文投影分别
  委托给 `TurnPipeline` / `TurnExecutor` / `ToolCallCoordinator`，避免巨型 Service；
- 事务只包住单次 entry 追加或状态更新；LLM、工具、审批等待期间不持有事务；
- 通过 domain 端口访问仓储与外部能力；不直接依赖具体实现。

## 计划包结构

```text
com.piagent.application
├── session      创建/分支/fork/查询会话
├── agent        run 生命周期、turn、工具协调、事件发布
├── model        模型档案管理
├── tool         工具目录查询
├── governance   策略与审批用例
├── context      压缩与 skills 刷新
├── plugin       插件安装/启停用例
└── readmodel    查询投影
```

## 事务与并发

- 同一 session 同时只允许一个活跃 run；
- 追加 entry 使用进程内短锁 + 数据库 `version` CAS；
- 取消令牌贯穿 LLM、工具、审批与子代理；
- 持久化失败直接终止 run，不做静默重试。

## 当前状态

M1 完成 `SessionApplicationService`；M2 完成 `ModelProfileApplicationService`
（创建、更新、启停、查询、启用校验）与对应 Command；M3 完成
`ToolCatalogApplicationService`（工具目录 + 当前工作区策略效果）；M4 完成
`application.agent`：Agent Loop、run 运行时与事件中心、turn 流水线、工具协调器、
短事务 entry 追加器；M5 完成 `PluginApplicationService`（安装、启停、查询、
启动恢复）与 run 级工具快照接入：`RunRuntime.reserve` 冻结工具目录，
`TurnPipeline` 与 `ToolCallCoordinator` 只使用该快照。M6 完成
`application.governance`（`ApprovalApplicationService`、`ApprovalGateService`、
`ApprovalWaitRegistry`、`ApprovalEventPublisher`、`ToolPolicyApplicationService`），
并把工具预检拆到 `ToolCallPreflight`。

M7 完成 `application.context`：

| 类型 | 职责 |
|---|---|
| `ContextCompactionService` | 测量→触发→规划→摘要→收敛→回调落库（不直接依赖仓储） |
| `CompactionSummarizer` | 调用模型生成摘要并解析为结构化小节 |
| `CompactionComponents` | 领域组件集合，避免超长构造参数 |
| `ContextMeasurementFactory` | 投影 + 预算推导（大小窗口都合法） |
| `CompactionApplicationService` | 手动 `/compact`（仅 IDLE） |
| `SkillApplicationService` | 技能列表/查询/正文/刷新 |
| `RunCompactionStep` | run 内自动压缩与溢出压缩，发布 `compaction.*` |
| `ContextCompactionState` | run 级 usage 锚点、上次压缩量、自动压缩次数 |
| `TurnStreamer` / `RunSteps` / `SkillInvocationResolver` | 从 Agent Loop 拆出的内聚职责 |
| `PromptSections` / `SkillCommand` | system prompt 小节与 `/skill:` 语法 |

同时修正了 M1 遗留的 token 估算口径（统一为 `EntryTokenCounter`），中文会话才能正确触发压缩。

M8 完成 `application.subagent`：

| 类型 | 职责 |
|---|---|
| `SubagentSessionFactory` | 构造子会话（沿用模型与沙箱、`origin=SUBAGENT`、深度 +1） |
| `SubagentExecutionService` | 持久化子会话、启动子 run、有界等待、聚合结论 |
| `SubagentService` | 深度校验、工具子集、组装提示词、委派 |
| `SubagentToolInvoker` | `@Primary` 的 `ToolInvoker`，截获 `subagent` 并转发其余工具 |
| `SubagentRegistry` | `subagent` 工具定义（与执行者成对出现） |

`AgentLoopService` 用 `ObjectProvider` 延迟解析，避免“子代理在工具管线内启动 Agent Loop”
造成的结构性循环依赖。application 共 111 个测试。详见 [M8 记录](../docs/milestones/M8.md)。
