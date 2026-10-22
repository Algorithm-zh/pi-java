# 02 领域模型

> 状态：M0 冻结。本文是领域层的实现契约：包名、类型名、不变量一旦落地，变更必须同步更新本文与测试。
>
> 领域层只依赖 JDK。所有跨上下文的引用只允许“身份引用”（ID）或只读值对象，不允许跨上下文调用聚合行为。

## 1. 共享内核 `domain.shared`

| 类型 | 形态 | 职责 |
|---|---|---|
| `Ulid` | record / 静态工厂 | 26 位 Crockford Base32，时间有序；`generate(Instant)`、`parse(String)` |
| `IdGenerator` | 接口 | `String newId()`；生产实现基于 `Ulid`，测试实现可预测 |
| `DomainErrorCode` | enum | 稳定错误码，如 `SESSION_NOT_FOUND`、`INVALID_BRANCH`、`TOOL_CALL_MISMATCH` |
| `DomainException` | RuntimeException | 携带 `DomainErrorCode` 与中文消息；应用层翻译为 API 错误 |
| `Guard` | 工具类 | `require(boolean, code, message)`、非空/长度/范围校验，减少重复 if |

`Clock` 直接使用 `java.time.Clock`，不重复造接口；测试注入 `Clock.fixed(...)`。

## 2. 会话上下文 `domain.session`

会话上下文只回答三个问题：**记录什么、当前在哪、发给模型什么。**

### 2.1 聚合根 `ChatSession`

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `SessionId` | ULID |
| `parentSessionId` | `SessionId?` | fork / subagent 的父会话；父会话被删除时为 null（孤儿态） |
| `rootSessionId` | `SessionId` | 根会话，便于整棵树检索 |
| `forkFromEntryId` | `EntryId?` | fork 的父会话起点 |
| `depth` | `int` | 用户会话为 0，子代理逐级 +1 |
| `origin` | `Origin` | `USER / FORK / SUBAGENT` |
| `title` | `String` | 展示标题，最长 255 |
| `cwd` | `Path` | 工作目录，同时作为沙箱根 |
| `modelProfileId` | `String` | 逻辑关联模型档案，不做跨模块外键 |
| `thinkingLevel` | `ThinkingLevel` | `OFF / LOW / MEDIUM / HIGH` |
| `sandbox` | `SandboxSnapshot` | 可写根、拒绝规则快照；会话创建时固定 |
| `status` | `SessionStatus` | `IDLE / RUNNING / WAITING_APPROVAL / ARCHIVED` |
| `leafEntryId` | `EntryId?` | 当前分支末端；新会话为 null |
| `version` | `long` | 乐观锁版本 |

行为：

- `createRoot(id, title, config)` / `createChild(id, parent, forkFromEntryId, origin, title, config)`；
- `rename(String)`、`changeModel(modelProfileId, thinkingLevel)`、`archive()`；
- `moveLeaf(EntryId target)`：只移动指针，不复制 entry；目标合法性由 `SessionBranchPolicy` 校验；
- `markRunning()` / `markWaitingApproval()` / `markIdle()`：受状态机约束；
- `withVersion(long)`：仓储 CAS 成功后同步版本；
- `isEmpty()`：判断是否尚无 entry。

父会话被删除时，外键会把子会话的 `parent_session_id` 置空。此时子会话以“孤儿”形态存在：
`origin` 仍为 `FORK/SUBAGENT`、`depth > 0`、`rootSessionId` 保留原根 ID，但 `parentSessionId = null`。
创建路径始终要求父会话；孤儿态只允许出现在持久化重建时，避免把删除父会话变成级联删除子会话。

`ChatSession` 不持有完整 entry 列表。entry 可能很多，聚合只保存“当前指针 + 元数据”，
entry 由 `SessionEntryStore` 按需读取。这样避免每次追加都加载整棵会话树。

### 2.2 实体 `SessionEntry`

不可变 record：

```text
SessionEntry(
  EntryId id,
  SessionId sessionId,
  long seq,
  EntryId parentId,        // 首条为 null
  EntryType type,
  int payloadVersion,      // 从 1 开始，只加字段不改语义
  EntryPayload payload,
  int tokenEstimate,
  Instant createdAt
)
```

`entry_type` 与 payload 必须匹配：

| EntryType | Payload | 用途 |
|---|---|---|
| `MESSAGE` | `MessagePayload(AgentMessage)` | 用户/助手/工具结果消息 |
| `CUSTOM_MESSAGE` | `CustomMessagePayload(customType, content, display, details)` | 插件注入且参与上下文的消息 |
| `COMPACTION` | `CompactionPayload(summary, firstKeptEntryId, tokensBefore, details)` | 压缩摘要 |
| `BRANCH_SUMMARY` | `BranchSummaryPayload(fromEntryId, summary, details)` | 分支切换时保留旧分支上下文 |
| `MODEL_CHANGE` | `ModelChangePayload(modelProfileId, thinkingLevel)` | 模型/思考级别切换 |
| `CUSTOM` | `CustomPayload(customType, data)` | 扩展状态，不进入 LLM 上下文 |
| `LABEL` | `LabelPayload(targetEntryId, label)` | 书签/检查点 |
| `SESSION_INFO` | `SessionInfoPayload(name)` | 会话展示名 |
| `APPROVAL` | `ApprovalPayload(approvalId, toolCallId, toolName, decision, reason, decidedBy)` | 审批审计 |
| `RUN_SUMMARY` | `RunSummaryPayload(runId, stopReason, usage, turns, toolCalls, durationMs, errorMessage)` | 每次 run 结束摘要 |

### 2.3 消息模型（sealed）

```text
AgentMessage
├── UserMessage(content: List<ContentBlock>, timestamp)
├── AssistantMessage(content, provider, model, usage, stopReason, errorMessage?, timestamp)
├── ToolResultMessage(toolCallId, toolName, content, isError, details: Map<String,Object>, timestamp)
├── CompactionSummaryMessage(summary, tokensBefore, timestamp)
└── BranchSummaryMessage(summary, fromEntryId, timestamp)

ContentBlock
├── TextBlock(text)
├── ThinkingBlock(thinking, signature?)
└── ToolCallBlock(id, name, arguments: Map<String,Object>)
```

后两种消息只由投影器根据 `COMPACTION` / `BRANCH_SUMMARY` entry 生成，
不会作为 `MESSAGE` entry 再次落库。

约束：

- `ToolCallBlock.id` 在一条 assistant 消息内唯一；
- `ToolResultMessage.toolCallId` 必须能在**同一分支**上找到对应 tool call；
- `details` 只允许 JSON 可序列化值（String/Number/Boolean/List/Map），由基础设施负责落库；
- `AssistantMessage.usage` 使用会话侧的 `TokenUsage`，由模型侧 `Usage` 映射而来；
- 图片/音频块 v0.1 不支持，模型层遇到直接返回明确错误。

### 2.4 值对象

| 类型 | 说明 |
|---|---|
| `SessionId` / `EntryId` | 包装 `Ulid`，类型安全，禁止互换 |
| `Origin` | `USER / FORK / SUBAGENT` |
| `SessionStatus` | `IDLE / RUNNING / WAITING_APPROVAL / ARCHIVED` |
| `EntryType` | 上表 10 种 |
| `TokenUsage` | `input/output/cacheRead/cacheWrite/totalTokens`，全为非负 |
| `SandboxSnapshot` | `writableRoots` + `denyGlobs` 的只读快照；创建会话时从治理上下文复制，避免运行中策略变更改变历史会话的沙箱语义 |
| `ProjectedContext` | `List<AgentMessage> messages, List<EntryId> trace, int estimatedTokens` |

### 2.5 领域服务

#### `SessionContextProjector`

输入：目标 leaf（默认会话当前 leaf）、该会话全部 entry。
输出：`ProjectedContext`。

算法：

1. 从 leaf 沿 `parentId` 回溯到根，得到**唯一分支**，再反转为时间序；
2. 找到分支上**最后一个** `COMPACTION` entry；
3. 若有压缩：先产出 `CompactionSummaryMessage(summary)`，再取 `firstKeptEntryId` 到压缩点之间的 entry；
4. 跳过 `CUSTOM`、`LABEL`、`SESSION_INFO`、`RUN_SUMMARY`、`APPROVAL`；
5. `CUSTOM_MESSAGE` 转为 `UserMessage` 或带标记的上下文消息；
6. `BRANCH_SUMMARY` 转为 `BranchSummaryMessage`；
7. 校验工具调用配对：每个 toolResult 必须找到前置 toolCall，且 toolCall 都有结果（未完成时允许在尾部悬空并由恢复流程处理）；
8. 汇总 token 估算。

投影是**纯函数**，不访问数据库、不调用 LLM；相同 entry 序列必得相同结果，便于黄金样本测试。

#### `SessionBranchPolicy`

- 目标 entry 必须属于同一会话；
- 目标不能是 toolResult（会把调用与结果拆开）；
- 允许回到任意 user/assistant/compaction/custom 节点；
- fork 起点同样必须是本会话中非 toolResult 的 entry；
- 分支不复制 entry，不重写 `seq`。

### 2.6 端口

```java
interface SessionRepository {
    Optional<ChatSession> findById(SessionId id);
    void insert(ChatSession session);
    void update(ChatSession session);          // version CAS
    List<ChatSession> findByCwd(Path cwd, int limit);
    List<ChatSession> findChildren(SessionId parentId);
}

interface SessionEntryStore {
    void append(SessionEntry entry);           // (session_id, seq) 唯一
    List<SessionEntry> loadBranch(SessionId sessionId, EntryId leafId);
    Optional<SessionEntry> findById(SessionId sessionId, EntryId entryId);
    long countBySession(SessionId sessionId);
}
```

追加 entry 与更新 `leaf_entry_id/version` 必须在**同一短事务**内完成；
应用层先校验 session 状态，再用 CAS 处理并发写。

## 3. 运行时上下文 `domain.agent`

### 3.1 聚合根 `AgentRun`（仅内存）

M4 的实现是**不可变状态快照**，每次变更返回新 record，而不是可变聚合：

| 字段 | 说明 |
|---|---|
| `id` | ULID `RunId` |
| `sessionId` | 所属会话 |
| `status` | `RUNNING / COMPLETED / FAILED / ABORTED` |
| `budget` | `RunBudget(maxTurns, maxToolCalls, maxDuration)` |
| `turns` | 已完成的模型请求数（int） |
| `toolCalls` | 已执行的工具调用数（int） |
| `usage` | 累计 `TokenUsage` |
| `startedAt / finishedAt` | 开始/结束时间；运行中 `finishedAt=null` |
| `stopReason` | `RunStopReason`；运行中为 null |
| `errorMessage` | 失败原因（≤4000 字符，仅 ERROR 必填） |

行为：`start`、`recordTurn(usage)`、`recordToolCalls(n)`、`finish(reason, error, at)`、
`isTerminal()`、`durationMillis(now)`。所有变更先校验 `RUNNING`，终态不可再迁移。

`AgentRun` 不持久化。run 结束后仅追加 `RUN_SUMMARY` entry；进程崩溃后不补写 run，
而是由应用层在下次发消息时把悬空会话自愈为 `IDLE`（见 [04 运行时与事件](04-runtime-and-events.md) §11）。

### 3.2 实体与值对象

- `RunId`：ULID 包装类型。
- `RunStatus`：四态，`isTerminal()` 判断是否结束。
- `RunStopReason`：`STOP / LENGTH / MAX_STEPS / CANCELLED / TIMEOUT / ERROR`。
- `RunBudget`：默认 20 轮、100 次工具调用、15 分钟；时长为正且不超过 1 小时；
  提供 `turnsExhausted`、`toolCallsExceeded`、`timedOut` 无副作用判定。
- `RunEvent(seq, runId, sessionId, type, at, data)`：`seq` 从 1 开始，`data` 冻结为
  不可变 Map，只放 JSON 友好的 JDK 值。
- `RunEventType`：`run.started`、`turn.started`、`message.started/delta/completed`、
  `tool.started/completed`、`run.completed/failed`、`heartbeat`、`replay.gap`。
- `ThinkingLevel`：`OFF / LOW / MEDIUM / HIGH`，属于会话配置，不属于 run 快照。
- `SubagentSpec` / `SubagentMode` / `SubagentResult`：M8 引入，当前仅保留规划。

### 3.3 领域服务

M4 **没有**独立的 TurnPolicy / StopReasonResolver：终止判定集中在应用层
`AgentLoopService`，因为它同时依赖预算、取消标志与流式结果，放在领域会引入运行态依赖。
领域只保留可独立测试的规则（预算判定、状态迁移、事件校验）。

M8 将引入 `SubagentPolicy`：校验深度、工具子集、沙箱交集、审批不得放宽；超限时移除
`subagent` 工具。

### 3.4 端口与注册表

M4 的活跃 run 注册表位于应用层（`ActiveRunRegistry`），因为它是进程内运行态，
不是领域事实：

```java
// application.agent.service
final class ActiveRunRegistry {
    void register(AgentRun run);               // 每会话原子占位，重复抛 RUN_ALREADY_ACTIVE
    Optional<AgentRun> find(RunId runId);
    Optional<AgentRun> findActiveBySession(SessionId sessionId);
    AgentRun update(RunId runId, UnaryOperator<AgentRun> change);
    boolean cancel(RunId runId);
    AgentRun awaitTerminal(RunId runId, Duration timeout);
}
```

“同一 session 只能有一个活跃 run”由 `register` 的 `putIfAbsent` 语义保证；领域层不感知锁。
事件端口由 `RunEventHub`（应用层）实现，domain 只定义 `RunEvent` 与类型。

## 4. 模型上下文 `domain.model`

### 4.1 聚合根 `ModelProfile`

`ModelProfile(id, displayName, endpoint, enabled, version)`。接入参数集中在值对象
`ModelEndpoint`，避免聚合与 API、DDL 各自散落十几个字段：

| 字段 | 说明 |
|---|---|
| `provider` | `OPENAI_COMPATIBLE / ANTHROPIC / FAKE` |
| `baseUrl` | API 根地址；写入时去掉尾部 `/` |
| `apiKeyEnv` | 环境变量名，不存真实密钥 |
| `modelName` | 发给 provider 的模型标识 |
| `contextWindow` / `maxOutputTokens` | 上下文与输出上限，输出不得超过上下文 |
| `capabilities` | 流式、工具调用、思考三项能力 |

行为：`create()`、`rename()`、`updateEndpoint()`、`enable()`、`disable()`、
`withVersion()`。编辑操作保留原 `version`，由仓储用旧版本做 CAS，成功后应用层再
同步新版本；`withVersion` 禁止版本回退。禁用不删除，避免破坏历史会话的可解释性。

构造约束：

- `FAKE` 允许空 `baseUrl/apiKeyEnv`，其他 provider 必须提供 http(s) 地址与合法环境变量名；
- `apiKeyEnv` 只匹配 `[A-Z][A-Z0-9_]{0,127}`，从格式上排除“把密钥填进来”；
- `maxOutputTokens <= contextWindow`，避免 provider 端参数自相矛盾。

### 4.2 值对象

| 类型 | 说明 |
|---|---|
| `ModelId` | 档案逻辑 ID，小写字母/数字/点/下划线/连字符，最长 64 |
| `ProviderType` | `OPENAI_COMPATIBLE / ANTHROPIC / FAKE` |
| `ModelRef` | `(provider, modelName)`，真正发给 provider 的标识 |
| `ModelCapabilities` | `streaming / toolCalls / thinking` 三项能力 |
| `Usage` | input / output / cacheRead / cacheWrite / totalTokens |

`Usage` 与会话侧 `TokenUsage` 分开：前者是 provider 账单口径，后者是会话累计口径，
由应用层显式映射，避免两个上下文共享可变状态。

### 4.3 端口

```java
interface ModelProfileRepository {
    Optional<ModelProfile> findById(ModelId id);
    List<ModelProfile> findAll();
    List<ModelProfile> findEnabled();
    void insert(ModelProfile profile);
    void update(ModelProfile profile);   // 以 profile.version 做 CAS
}

interface LlmGateway {
    LlmStream stream(LlmRequest request, LlmCallContext context);
}

interface LlmStream extends Iterator<LlmDelta>, AutoCloseable {
    Optional<LlmResponse> response();    // DoneDelta 之后可用
    boolean isCancelled();
    void cancel();
    void close();
}
```

`LlmRequest(profile, systemPrompt, messages, tools, thinkingLevel)` 复用会话的
`AgentMessage`，避免维护第二套消息层次；`LlmCallContext` 只携带总超时。
`LlmDelta` 是 sealed：`TextDelta`、`ThinkingDelta`、`ToolCallDelta`、`DoneDelta`。
`LlmResponse` 包含最终内容块、`Usage`、`AssistantStopReason` 与错误信息。

调用约定（详见 `LlmStream` Javadoc）：

- `hasNext()` 驱动真实网络读取；连接是惰性的，构造流不发起请求；
- 正常结束先产出 `DoneDelta`，随后 `response()` 可用；
- 错误从 `hasNext()/next()` 抛 `LlmGatewayException`，不会伪装成 delta；
- `cancel()` 与 `close()` 幂等，取消后迭代立即返回 false。

`LlmErrorCode` 区分可重试（`NETWORK/TIMEOUT/RATE_LIMITED/SERVER_ERROR`）与不可重试
（`AUTHENTICATION/INVALID_REQUEST/CONTEXT_OVERFLOW/MALFORMED_RESPONSE/MISSING_API_KEY/
UNSUPPORTED_PROVIDER/CANCELLED`）。重试策略只在 adapter 内执行，领域层不重试。

### 4.4 领域服务

`ModelSelectionPolicy`：

- `requireEnabled(profile)`：停用档案返回 `MODEL_DISABLED`；
- `requireCompatible(profile, needsTools, thinkingLevel)`：流式、工具、思考能力不足时
  直接报错，不在运行中静默降级。

“未指定模型时选择唯一默认档案”属于 run 创建用例，M4 接入 Agent Loop 时实现；
当前应用层只暴露 `listEnabled`，不替用户猜模型。


## 5. 工具上下文 `domain.tool`

### 5.1 值对象

| 类型 | 说明 |
|---|---|
| `ToolName` | 小写字母开头，允许小写字母/数字/点/下划线/连字符，最长 128；插件工具在 M5 强制 `pluginId.` 前缀 |
| `ToolCallId` | LLM 返回的调用 ID，包装 String |
| `ExecutionMode` | `PARALLEL / SEQUENTIAL` |
| `ToolRisk` | `LOW / MEDIUM / HIGH / CRITICAL` |
| `ToolDescriptor` | 不可变目录项：名称、描述（≤1024）、JSON Schema 字符串（≤20000）、风险、执行模式、超时（≤10 分钟） |
| `ToolResult` | 调用 ID、工具名、文本内容、`error` 标志、结构化 `details`；失败不抛异常 |
| `ToolInvocation` | 运行态：调用 ID、工具名、不可变参数、调用开始时冻结的 `SandboxPolicy` |

工具定义**不是聚合根**：它没有独立生命周期，由 `ToolCatalog` 维护不可变快照。

### 5.2 领域服务与端口

| 类型 | 职责 | 状态 |
|---|---|---|
| `ToolCatalog` | 不可变目录；合并内置与插件工具、拒绝重名、按名查询；插件工具合并与白名单过滤在 M5 扩展 | M3 已实现 |
| `ToolArgumentGuard` | 拒绝未知参数，读取必填字符串、可选字符串/整数/布尔并校验上限；JSON Schema 深度校验由具体 handler 完成 | M3 已实现 |
| `ToolCallCoordinator`（应用层） | 依据 `ExecutionMode` 分批：整批 `PARALLEL` 才并行，否则整批串行，结果保持模型给出的顺序；设计稿中的 `ToolExecutionPlanner` 已并入此类 | M4 已实现 |
| `ToolRegistry` | 端口：`descriptors()`、`find(ToolName)`、`require(ToolName)` | M3 已实现 |
| `ToolInvoker` | 端口：`ToolResult invoke(ToolInvocation)`；超时与中断由适配器处理，M4 起每个工具执行前检查 run 取消标志 | M3 已实现；M4 已接入取消 |

`ToolCatalog` 在每次 run 开始时取快照，run 期间插件卸载不影响本次执行。

## 6. 治理上下文 `domain.governance`

### 6.1 聚合根 `ToolPolicyRule`

| 字段 | 说明 |
|---|---|
| `id` | 自增主键，0 表示未持久化 |
| `scope` | `USER / WORKSPACE` |
| `workspaceKey` | `sha256(normalized cwd)`；USER 作用域使用 `WorkspaceKey.GLOBAL`（64 个 `0`） |
| `toolName` | 工具名 |
| `effect` | `ALLOW / ASK / DENY` |
| `approvalMode` | `ONCE / SESSION / ALWAYS` |
| `riskLevel` | 覆盖默认风险 |
| `timeoutMs` | 覆盖工具默认超时，100–600000ms，可空 |
| `configJson` | 扩展配置，只读字符串 |
| `version` | 乐观锁，从 1 开始 |

唯一键 `(scope, workspace_key, tool_name)`。规则缺失时按 `ToolAccessPolicy` 使用风险默认值：

- `LOW` → `ALLOW`；
- `MEDIUM / HIGH` → `ASK`；
- `CRITICAL` → `DENY`。

M3 的四个只读工具均为 `LOW`，因此默认放行。M6 计划让 `write/edit` 为 `HIGH`（默认 ASK）、
`bash` 为 `CRITICAL`（默认 DENY，需用户显式创建策略才可执行）。工具目录中不存在的名称
在取 `ToolDescriptor` 时即失败，不进入策略解析。

优先级：`WORKSPACE` 规则 > `USER` 规则 > 风险默认值；`DENY` 一旦命中即拒绝，不允许被更宽规则覆盖。

### 6.2 聚合根 `ApprovalRequest`（M6 实现）

状态机：

```text
PENDING ──approve──► APPROVED
   │────deny───────► DENIED
   │────expire─────► EXPIRED
   └────cancel─────► CANCELLED
```

终态不可再迁移。字段：`ApprovalId`、`sessionId`、`runId`、`toolCallId`、工具名、
`argsDigest`、`argsRedactedJson`、风险、状态、原因、决策者（`USER/POLICY/TIMEOUT`）、
决策时间、过期时间、创建时间。

`ApprovalPort` 是“向用户发起询问”的端口；领域层不关心 Web SSE 还是 CLI 提示。

### 6.3 路径沙箱（M3 已实现）

```java
record SandboxPolicy(Path workspaceRoot, List<Path> writableRoots,
                     boolean allowReadOutsideWorkspace) {
    static SandboxPolicy readOnly(Path workspaceRoot);
    static SandboxPolicy workspaceWritable(Path workspaceRoot);
}

final class PathSandbox {
    Path resolveReadable(SandboxPolicy policy, String requested);
    Path resolveWritable(SandboxPolicy policy, String requested);
}
```

规则：

1. 空白路径表示工作区根；相对路径基于根解析，绝对路径原样接受；
2. `normalize()` 后转为绝对路径，`..` 归一化后逃逸根目录即拒绝；
3. 含 NUL 或 `InvalidPathException` 返回 `PATH_INVALID`；
4. 读路径必须落在 `workspaceRoot` 内（除非 `allowReadOutsideWorkspace`）；
   写路径必须落在 `writableRoots` 之一；越界返回 `PATH_OUTSIDE_WORKSPACE`；
5. 符号链接检测不在领域层做：infrastructure 的 `RealPathResolver` 先让 `PathSandbox`
   做词法判定，再用 `toRealPath()` 取得真实路径并重新比较；遍历工具不跟随符号链接。
   领域层保持纯函数、可单测、跨平台。

### 6.4 领域服务

| 服务 | 职责 | 状态 |
|---|---|---|
| `ToolAccessPolicy` | 合并工作区/用户规则与风险默认值，输出 `PolicyEffect` | M3 已实现 |
| `PathSandbox` | 纯路径判定 | M3 已实现 |
| `ToolPolicyRepository` | 按工作区（含全局）查询规则、插入/CAS 更新 | M3 已实现 |
| `ApprovalExpiryPolicy` | 默认 30 分钟过期，run 取消时立即取消等待 | M6 实现 |

### 6.5 端口

- `ToolPolicyRepository`（M3）：`findByWorkspace(WorkspaceKey)`、`find(scope, key, tool)`、
  `insert`、CAS `update`；
- `ApprovalRepository`、`ApprovalPort`（M6）：审批决策**双写**，更新待办投影 + 追加
  `APPROVAL` entry；审计以 entry 为准。

## 7. 上下文与技能 `domain.context`

| 类型 | 职责 |
|---|---|
| `TokenEstimate` | 估算值 + 方法标记（`HEURISTIC / USAGE_ANCHOR`） |
| `ContextBudget` | 上下文窗口、保留输出 token、保留近期 token |
| `CompactionPlan` | 待摘要 entry、`firstKeptEntryId`、split-turn 前缀、估算前后 token |
| `TokenEstimator` | 字符启发式 + 最近一次 usage 校准；CJK 按 1 字符≈1 token，ASCII 按 4 字符≈1 token |
| `CompactionPlanner` | 从最新向前保留 `keepRecentTokens`，切点只能是 user/assistant/custom，绝不切 toolResult |
| `SkillDescriptor` | name、description、文件路径、来源 |
| `SkillCatalog` | 已发现 skill 的不可变目录；按 name 查询 |
| `SkillPromptFormatter` | 只把 name + description 注入 system prompt，正文按需读取 |

压缩失败时必须能结束：

- 摘要请求的估算 token 必须小于被遮蔽 token；
- 若估算不收敛，降级为“只剪枝 + 保留尾部”，不进入无限压缩；
- 一次 run 内自动压缩最多重试一次。

`SkillSource` 是端口：扫描目录、读取正文；文件解析与 YAML 处理在 infrastructure。

## 8. 扩展上下文 `domain.extension`

| 类型 | 说明 |
|---|---|
| `PluginId` | `groupId:artifactId` 或 manifest id；全局唯一 |
| `PluginDescriptor` | 名称、版本、API 主版本、主类、描述 |
| `ApiVersion` | 解析 `1.x`，主版本必须为 1 |
| `PluginStatus` | `INSTALLED / ACTIVE / DISABLED / FAILED` |
| `PluginInstallation` | 聚合根：id、version、apiVersion、jarPath、checksum、status、configJson、lastError、时间 |
| `PluginCompatibilityChecker` | API 主版本、重复 ID、内置工具冲突校验 |
| `PluginActivationPolicy` | 允许的状态迁移：INSTALLED→ACTIVE/DISABLED；ACTIVE→DISABLED/FAILED；FAILED→DISABLED |

端口：`PluginRepository`、`PluginRuntime`（加载/卸载/当前工具快照）。
插件注册的具体类型在 `plugin-api`，领域层不引用；infrastructure 将插件工具映射为 `ToolDescriptor`。

## 9. 跨上下文不变量

| 编号 | 不变量 | 执行位置 |
|---|---|---|
| INV-01 | entry 追加后不可修改；seq 在会话内连续唯一 | store + 数据库唯一键 |
| INV-02 | entry 的 parent 必须属于同一 session | 应用层 + store 校验 |
| INV-03 | `leaf_entry_id` 必须属于该会话且不是 toolResult | `SessionBranchPolicy` |
| INV-04 | 每个 toolResult 都能匹配同分支上的 toolCall | 投影器 + 恢复流程 |
| INV-05 | 压缩切点不得落在 toolResult 上 | `CompactionPlanner` |
| INV-06 | 同一 session 同时最多一个活跃 run | `RunRegistry` 原子注册 |
| INV-07 | 审批终态不可逆；决策必须写 entry | `ApprovalRequest` + 应用层 |
| INV-08 | 写路径必须在可写根内，真实路径复核 | `PathSandbox` + `RealPathResolver` |
| INV-09 | 子代理工具集合 ⊆ 父工具集合；沙箱只收紧不放宽 | `SubagentPolicy` |
| INV-10 | 插件 API 主版本必须为 1；不得覆盖内置工具 | `PluginCompatibilityChecker` |
| INV-11 | 模型档案不存密钥，只存环境变量名 | 聚合构造校验 |
| INV-12 | run 结束后必须留下 `RUN_SUMMARY` | Agent Loop 收尾 + 启动恢复 |

## 10. 相关文档

- [数据库设计](03-database-design.md)
- [运行时与事件](04-runtime-and-events.md)
- [插件 SDK](06-plugin-sdk.md)
