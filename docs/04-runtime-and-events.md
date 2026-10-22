# 04 运行时与事件

> 状态：M4 已实现 Agent Loop、工具管线、SSE 与重放；M6 已实现审批等待、写工具与
> 策略 API；M7 已实现上下文压缩与 Skills。子代理在 M8 落地。运行时由 application 层
> 编排，domain 只提供规则与快照；所有外部等待（LLM、工具、审批、摘要）都不持有数据库事务。

## 1. 运行时组件

```text
trigger.http（SessionController / RunController / CompactionController / SkillController）
        │
        ▼
AgentLoopService
  ├─ RunDispatcher          全局并发许可；满额立即拒绝
  ├─ ActiveRunRegistry      每会话一个活跃 run + 取消标志
  ├─ RunRuntime             预留 → 追加用户消息 → 收尾 → RUN_SUMMARY
  ├─ SkillInvocationResolver   /skill:name args → 展开后的用户消息 + 显式技能
  ├─ RunSteps               守卫（取消/超时/轮数）与工具批次执行
  ├─ RunCompactionStep      自动压缩 / 溢出压缩 + compaction.* 事件
  ├─ TurnStreamer           模型调用 + 溢出后压缩重试
  ├─ TurnPipeline
  │    ├─ SessionEntryStore + SessionContextProjector（分支投影）
  │    ├─ SystemPromptBuilder ──► PromptSections + SkillSource + WorkspacePromptSource
  │    ├─ TurnExecutor ──► LlmGateway ──► Fake / OpenAI-compatible
  │    └─ ToolCallCoordinator ──► ToolCallPreflight ──► ToolAccessPolicy
  │                              ├─ ApprovalGate ──► ApprovalApplicationService
  │                              └─ ToolInvoker / SessionEntryAppender
  └─ RunEventPublisher ──► RunEventHub ──► RunSseChannel ──► SSE / CLI
```

职责边界：

| 组件 | 只做什么 | 不做什么 |
|---|---|---|
| `AgentLoopService` | 推进 turn、判定终止、统一收尾 | 直接读写 SQL、解析 SSE |
| `RunDispatcher` | 全局并发许可，拒绝而非排队 | 会话级互斥 |
| `RunRuntime` / `ActiveRunRegistry` | run 快照、每会话唯一活跃 run、取消标志、事件通道 | 业务终止判定 |
| `TurnPipeline` | 投影、组装请求、流式调用、工具批次 | 推进状态机 |
| `TurnExecutor` | 读取 LLM 流、发布增量、取消后返回部分文本 | 持久化 entry |
| `TurnStreamer` | 一轮模型调用与溢出压缩重试（至多一次） | 状态机推进、工具执行 |
| `RunSteps` | 守卫判定、工具批次与预算计数 | 模型调用 |
| `RunCompactionStep` | 触发判定、调用压缩编排、发布 `compaction.*` | 生成摘要（委托 `CompactionSummarizer`） |
| `SkillInvocationResolver` | 解析 `/skill:` 并展开正文 | 缓存与文件扫描 |
| `ToolCallCoordinator` | 消费预检计划、审批等待、并行/串行执行、成对回写结果 | 决定模型下一步 |
| `ToolCallPreflight` | 策略解析、注册校验与预算裁剪，产出执行计划 | 调用工具、等待审批 |
| `SessionEntryAppender` | 短事务追加 entry 与状态迁移 | 执行工具、调用模型 |
| `RunEventPublisher` / `RunEventHub` | 事件数据形状、序号、缓冲、重放 | 业务判断、持久化 |

## 2. 一次 Run 的生命周期

### 2.1 启动

```text
1. 读取会话；若数据库为 RUNNING/WAITING_APPROVAL 但进程内无活跃 run，
   先自愈为 IDLE（单实例前提）
2. RunDispatcher.tryAcquire：全局许可满额立即返回 AGENT_BUSY（HTTP 429）
3. ActiveRunRegistry 预留 runId + 取消标志
4. 短事务：追加用户 MESSAGE entry，会话 RUNNING，移动 leaf
5. 解析模型档案（档案缺失/停用则回滚预留并恢复 IDLE）
6. 建立事件通道，发布 run.started（含模型、预算、开始时间）
7. 虚拟线程执行 run，进入 turn 循环；HTTP 请求立即返回 runId
```

会话写锁只在“追加 entry / 移动 leaf / 状态变更”时短暂持有，**不覆盖整个 run**；
单 session 单 run 由进程内注册表保证，entry 顺序由锁 + `(session_id, seq)` 唯一键保证。

### 2.2 Turn 循环

```text
while true:
    检查取消、运行时长、最大轮数（超限写 RUN_SUMMARY 后结束）
    publish turn.started
    读取当前分支 → 投影上下文 → 组装 system prompt 与工具目录
    stream = LlmGateway.stream(request)
    逐 delta publish message.delta（text / thinking / toolCall）
    组装 assistant 消息并短事务追加 entry
    publish message.completed（entryId、stopReason、usage）
    recordTurn(usage)

    若本次被取消：finish(CANCELLED)，保留已流出的部分文本
    若 assistant.stopReason == ERROR：finish(ERROR)
    若没有 toolCall：finish(STOP 或 LENGTH)
    执行整批工具 → 记录执行数 → 若批内预算耗尽则 finish(MAX_STEPS)
    进入下一轮
```

关键点：

- assistant 消息与 toolResult 分别追加，不合并成一条“大消息”；
- toolResult 与 toolCall 成对：未注册、策略拒绝、审批不可用、预算不足、取消都写 `isError=true` 结果；
- 模型返回 `TOOL_USE` 但没有可解析 toolCall 时，由 adapter 报 `MALFORMED_RESPONSE`，run 以 ERROR 收尾；
- M4 不实现插件 follow-up；assistant 无 toolCall 即结束本轮 run。

### 2.3 收尾

无论成功、失败、取消，统一走 `RunRuntime.finish`：

```text
1. AgentRun.finish 计算终态：CANCELLED/TIMEOUT → ABORTED，ERROR → FAILED，其余 → COMPLETED
2. 短事务追加 RUN_SUMMARY entry（runId、stopReason、usage、turns、toolCalls、duration）
3. 会话置回 IDLE
4. 从 ActiveRunRegistry 移除并发布终态事件
5. 发布 run.completed 或 run.failed；关闭事件通道（缓冲保留供重放）
6. SSE 通道收到终态后完成 emitter
```

若 RUN_SUMMARY 持久化失败：强制会话回到 IDLE、run 标记 FAILED、记录高优先级日志；
会话在下次发消息时由惰性自愈修复，不阻塞后续使用。

## 3. 状态机

### 3.1 会话状态

```text
IDLE ──start──► RUNNING ──need approval──► WAITING_APPROVAL
  ▲                │                              │
  │                └──────── approve/deny ◄───────┘
  │                              │
  └──────── finish ◄─────────────┘

任意状态 ──archive──► ARCHIVED（只读，不可再启动 run）
```

### 3.2 Run 状态

M4 的 `RunStatus` 只有四个值，不由审批或压缩派生中间状态：

```text
RUNNING ──STOP/LENGTH/MAX_STEPS──► COMPLETED
   │
   ├──ERROR──────────────────────► FAILED
   └──CANCELLED/TIMEOUT──────────► ABORTED
```

`WAITING_APPROVAL` 是**会话**状态：M6 审批等待期间 run 仍为 `RUNNING`，会话进入
`WAITING_APPROVAL`，决策后回到 `RUNNING`。压缩在 M7 是 run 内部的同步步骤，不新增
Run 状态。终态不可迁移；对已结束 run 的取消请求返回 `RUN_NOT_FOUND`（注册表只保留
终态快照，不保留可再次取消的活跃身份）。

## 4. 工具管线

### 4.1 顺序

M4 已实现、M6 完成审批接入的完整管线：

```text
ToolCallBlock
  -> 预检：ToolRegistry 取 ToolDescriptor（未注册 → isError 结果）
  -> ToolAccessPolicy.resolve（workspace > user > 风险默认）
       ALLOW -> 继续
       ASK   -> 创建审批待办并阻塞等待；APPROVED 才执行
       DENY  -> 生成 isError 结果
  -> 预算检查：超出本次 run 剩余工具次数 → isError 结果
  -> 整批判断：全部 PARALLEL 且多于一个 → 并行；否则按模型顺序串行
  -> ToolInvoker：虚拟线程执行、按 descriptor 超时、异常包装
  -> 按原 toolCall 顺序追加 toolResult entry
  -> 发布 tool.started / tool.completed
```

M5 已把插件工具接入这条管线，但**没有**插件拦截器或观察器；若后续引入，
只允许 allow/deny 或只读观察，不能改参数：

```text
  -> 插件工具与内置工具同样经过策略、审批与沙箱（M5/M6）
  -> [未实现] 插件 ToolInterceptor / ToolResultObserver（M6/M7 评估）
```

### 4.2 并行策略

- 一批调用**全部**为 `PARALLEL` 且数量大于 1 时并行执行，否则整批串行；
  混入任一串行工具就退化，避免隐式破坏工具之间的顺序假设；
- 并行使用注入的 `toolExecutor`（虚拟线程），逐个 `CompletableFuture` 等待；
- 取消检查发生在每次调用开始前；已开始的工具依赖 `ToolInvoker` 超时/关闭，
  取消后仍会把这一批的结果逐条写成 `isError` 结果，保证配对；
- 结果按模型给出的 toolCall 顺序写 entry，与完成顺序无关；
- 工具数上限默认 100，超出部分不再执行但仍写“已达上限”的错误结果。

### 4.3 超时与输出

M3 已实现的只读工具：

| 工具 | 默认超时 | 输出限制 |
|---|---|---|
| `read` | 10s | 默认 400 行、最多 2000 行；单行 2000 字符；正文 80k 字符 |
| `ls` | 10s | 深度 ≤ 3；最多 500 项；正文 60k 字符 |
| `grep` | 20s | 最多扫描 5000 个文件、单文件 2 MB；默认 100/最多 500 条匹配；片段 300 字符；正文 80k 字符 |
| `glob` | 20s | 最多遍历 20000 个文件；默认 200/最多 1000 条路径；正文 60k 字符 |

`read/ls/grep/glob` 都跳过二进制文件（前 8KB 含 NUL），遍历不跟随符号链接，并跳过
隐藏目录与 `target/build/dist/node_modules/vendor/coverage/__pycache__/.venv/venv`。

M6 新增的写工具与 shell 工具：

| 工具 | 默认超时 | 输出与限制 |
|---|---|---|
| `write` | 10s | 单文件正文 ≤1M 字符；返回相对路径、字节数、行数、是否新建 |
| `edit` | 10s | 文件 ≤2 MB；`oldText` 默认必须唯一匹配；返回匹配/替换数与字节数 |
| `bash` | handler 默认 60s、上限 120s；策略 `timeoutMs` 尚未接入 | 合并 stdout/stderr，保留前 60k 字符并继续排空管道；超时终止整棵进程树 |
| `subagent`（M8） | 300s | 只返回摘要 + 子会话引用 |

超时视为工具失败：`BuiltInToolInvoker` 取消任务并返回 `isError=true`、
`details.errorCode=TOOL_TIMEOUT` 的结果，模型可据此调整，不终止整个 run。
线程中断返回 `CANCELLED`；handler 抛出的领域异常保留原错误码。

### 4.4 审批（M6 已实现）

```text
create PENDING -> publish approval.requested
    -> 用户 APPROVE -> APPROVED -> 执行工具
    -> 用户 DENY    -> DENIED   -> toolResult(isError=true)
    -> TTL 到期（默认 30m） -> EXPIRED -> 按 DENY 处理
    -> run 取消            -> CANCELLED -> 按 DENY 处理
```

- 审批参数在落库前脱敏：键名包含 key/token/secret/password/authorization/credential
  的值替换为 `***`，null 展示为 `<null>`；digest 基于原始参数的 canonical JSON
  （键排序、数字规范化）做 SHA-256，防止 UI 展示与实际执行参数漂移；
- HTTP 详情同时返回 digest 与脱敏参数；SSE 的 `approval.requested` 只含脱敏参数，
  **不包含 digest**，原始参数与密钥不进入事件通道；
- 决策接口只接受 PENDING；CAS 失败或重复决策返回 409 `APPROVAL_INVALID_STATE`；
  已过期时先落 EXPIRED 再返回冲突；
- 决策更新 `pi_approval_request` 与追加 `APPROVAL` entry 在同一短事务内；
  事务提交后才发布 `approval.resolved` 并唤醒等待线程；
- 等待线程每 200ms 检查 future、取消标志与过期时间；取消或过期由系统写入终态，
  决策者固定为 `system`；HTTP 决策者由服务端固定为 `local-user`（v0.1 无认证）；
- `approvalMode`（ONCE/SESSION/ALWAYS）已持久化，但 M6 执行器尚未实现“记住批准”，
  当前一律按每次都询问处理。

### 4.5 路径沙箱（M3 已实现）

`PathSandbox`（domain）只做纯路径判定：空白表示工作区根、相对路径基于根解析、
`normalize()` 后检查是否仍在允许的根内、拒绝 NUL 与非法路径。它不访问文件系统，
因此无法识别符号链接、Windows junction 或大小写别名。

`RealPathResolver`（infrastructure）负责物理复核：

1. 先调用 `PathSandbox.resolveReadable/resolveWritable` 得到词法绝对路径；
2. 读路径调用 `toRealPath()`，再把真实路径与真实工作区根比较；
3. 写路径对尚不存在的目标找到最近存在的祖先，取真实路径后拼回缺失段，
   再把结果与 `writableRoots` 的真实路径比较——既拒绝符号链接逃逸，又允许新建文件；
4. 路径不存在返回 `PATH_NOT_FOUND`，其他 IO/格式错误返回 `PATH_INVALID`，
   越界返回 `PATH_OUTSIDE_WORKSPACE`；
5. `ls/grep/glob` 的遍历使用 `NOFOLLOW_LINKS` 语义，不跟随目录链接，避免目录环与越界。

`bash`（M6）无法做路径级沙箱：它在 `cwd` 下启动子进程，通过审批、超时、输出截断和
“默认禁用危险命令模式”降低风险。文档与 UI 必须明确这一限制，不能宣称 bash 被完全沙箱化。

## 5. 取消

M4 已实现：

- `POST /sessions/{id}/abort` 设置 run 的取消标志；无活跃 run 返回 404 `RUN_NOT_FOUND`；
- 每轮开始前、每次流读取增量时、每个工具调用开始前检查取消标志；
- LLM 流收到取消后 `LlmStream.cancel()` 关闭 HTTP body，`TurnExecutor` 返回已累积的
  部分文本；assistant 消息以 `ABORTED` 落库，再写 `RUN_SUMMARY(CANCELLED)`；
- 取消后工具批次中尚未执行/未完成的调用仍会得到 `isError=true` 结果，保证配对；
- 内置工具超时由 `BuiltInToolInvoker` `Future.cancel(true)` 处理。

M6 已补齐：

- 审批等待者被唤醒并以 CANCELLED 结束；取消发生在审批前、等待中或批准后执行前，
  都会得到一条 `isError=true` 的 toolResult；
- `bash` 超时或线程中断时先终止整棵进程树，再返回错误结果。

M8 将继续补齐：子代理父取消级联子 run。

取消只影响当前 run，不归档会话；已进入终态的 run 忽略取消。

## 6. 上下文压缩（M7 已实现）

### 6.1 触发

```text
自动：上下文估算 > contextWindow - reserveTokens，且距上次压缩增长 ≥ minGrowthTokens
强制：provider 返回 CONTEXT_OVERFLOW（仅重试一次）
手动：POST /api/v1/sessions/{id}/compact（会话必须 IDLE）
```

预算推导（`ContextMeasurementFactory`）：

| 条件 | 取值 |
|---|---|
| 窗口 | `max(modelEndpoint.contextWindow, 1024)` |
| 预留 | `min(16384, 窗口 / 2)`，至少 1 |
| 保留近期 | `min(20000, 可用额度 × 80%)`，且必须小于可用额度 |

这样即使模型窗口只有 32k，也不会出现 `keepRecentTokens ≥ availableTokens` 的非法预算。

### 6.2 规划

1. 定位分支上最后一个 `COMPACTION`，从其 `firstKeptEntryId` 开始取参与上下文的消息；
2. 从最新向前累计，保留 `keepRecentTokens`；
3. 切点若落在 toolResult 上则向后跳过；全部落在 toolResult 时退回到最后一个非结果节点；
4. 若切点落在最后一个 user turn 内，标记 split-turn：摘要该 turn 前缀，保留后半段；
5. 上下文能整体放进保留窗口时空计划返回，调用方给出“无需压缩”的结论；
6. 旧摘要作为 `previousSummary` 参与本次摘要。

### 6.3 摘要

压缩请求 = 系统提示词（固定六小节格式）+ 一条独立 user 消息（旧摘要 + 历史文本），
**不传工具、不要求函数调用**，任何文本模型都能完成。

历史渲染规则：用户/助手/工具结果都保留，思考块丢弃，单条工具结果截断到 2000 字符，
历史总长 48k 字符（超出时保留最近部分并加“更早的内容已省略”）。

模型输出按标题解析为 `SummarySection`，支持 `## 目标`、`**目标**`、`目标：`、
`**目标**: 内容`、`Goal` 等写法；完全无法识别时把全文作为“目标”兜底。
“关键上下文”中的“读取文件：/ 修改文件：”会被抽取为 `details.readFiles / modifiedFiles`。

### 6.4 收敛与降级

```text
converged = 摘要 token < 被摘要原文 token
        且 压缩后 ≤ 可用额度
        且 压缩后 ≤ 压缩前 × 70%
```

不收敛时仍写入 `COMPACTION` entry，但 `details` 标记 `converged=false, prunedOnly=true`，
SSE `compaction.completed` 的 `prunedOnly` 为 true。配合“每 run 自动压缩一次”的上限，
保证不会出现“压缩 → 仍超限 → 再压缩”的死循环。

### 6.5 失败处理

| 场景 | 行为 |
|---|---|
| 自动压缩时摘要调用失败/为空 | 记录日志，返回未压缩，对话继续 |
| 溢出后压缩失败或无产出 | run 以 `ERROR` 结束，消息为“上下文超出模型窗口且无法压缩: …” |
| 溢出后压缩成功但重试仍溢出 | run 以 `ERROR` 结束，消息为“压缩后仍无法调用模型: …” |
| 手动压缩时内容不足/无需压缩 | HTTP 200，`applied=false` + 原因 |
| 手动压缩时会话非 IDLE | HTTP 409 `INVALID_STATE_TRANSITION` |

### 6.6 run 级状态（`ContextCompactionState`）

| 字段 | 用途 |
|---|---|
| `anchoredInputTokens` | 最近一次 provider 上报的输入 token，用于校准启发式估算 |
| `lastCompactedTokens` | 上次压缩时的上下文估算，供“最小增长量”判断 |
| `autoCompactions` | 本 run 已执行的自动压缩次数，上限 1 |

run 收尾（`RunRuntime.finish`）时清理，避免内存随运行次数增长。

## 7. System Prompt 与 Skills（M7 已实现）

### 7.1 小节顺序

| # | 小节 | 来源 | 上限 |
|---|---|---|---|
| 1 | 身份与行为约束 | 常量 | — |
| 2 | 环境：cwd、操作系统、思考级别 | 会话配置 | — |
| 3 | 工具目录与风险（按名称排序） | run 工具快照 | — |
| 4 | Skills 目录（仅 name + description） | `SkillSource` | 4000 字符 |
| 5 | 工作区约定 `AGENTS.md` | `WorkspacePromptSource` | 16k 字符 |
| 6 | 显式注入的技能正文 | `/skill:name` | 8k 字符 |

总长度上限 24k 字符，超限从尾部裁剪。提示词不含时间戳，保证前缀缓存有效、测试稳定。

### 7.2 技能发现与缓存

```text
skills/<name>/SKILL.md
---
name: deploy            # 可省略，默认取目录名（规范化为小写连字符）
description: 发布流程    # 可省略，默认取正文第一段
---
正文 Markdown
```

- 多个根目录按配置顺序优先，重名先到先得；
- 单层扫描，坏文件（过大、无法解析、缺 name）跳过而不中断；
- 缓存：目录快照 TTL 5 分钟 + 根目录 mtime 失效；正文按 `mtime + size` 缓存；
- `GET /api/v1/skills?refresh=true` 强制重扫。

### 7.3 `/skill:name args`

1. 以 `/skill:` 开头且第一段精确匹配某个技能名才视为指令；
2. 命中后把正文与“本次任务：args”拼成一条用户消息，**展开后的文本落库**；
3. 同一技能正文也会注入本轮 system prompt 第 6 小节；
4. 技能不存在、正文读取失败时按普通消息处理，不报错。

## 8. 子代理（M8）

- 工具形态 `subagent`，同步执行，参数：`mode=SPAWN|FORK`、`task`、`persona`、`tools`、`timeout`；
- `SPAWN`：新子会话，零父上下文；`FORK`：把父投影按预算裁剪后复制为子会话初始 entry；
- 子会话 `origin=SUBAGENT`，记录 `parent_session_id/root_session_id/depth/fork_from_entry_id`；
- 工具集合 = 父快照 ∩ 请求白名单；深度达到上限时从子工具目录移除 `subagent`；
- 子会话审批独立，不继承父的“本会话已允许”；沙箱根取父子交集，只收紧不放宽；
- 父取消级联子取消；子超时终止并返回 `ABORTED`；
- 结果写父会话 toolResult：`childSessionId`、`stopReason`、`usage`、摘要；`isError = stopReason != STOP`；
- 子代理内部异常不向父循环抛出，父模型始终拿到结构化工具结果。

## 9. SSE 事件

### 9.1 Envelope

```json
{
  "seq": 42,
  "runId": "01J...",
  "sessionId": "01J...",
  "type": "message.delta",
  "at": "2026-10-21T06:00:00.123Z",
  "data": {"kind": "text", "text": "正在读取"}
}
```

- SSE 的 `id` 字段为 `runId:seq`；`seq` 在每个 run 内从 1 单调递增；
- 每个 run 内存保留最近 500 条事件；通道在 run 结束后保留 30 分钟，超过 300 个通道时
  优先淘汰最旧的已关闭通道（保留到 200 个）；
- 心跳事件不带 id、不进入重放缓冲；
- `Last-Event-ID = runId:seq`：命中则回放 `seq+1` 起的事件；起点已被淘汰时先发送
  `replay.gap`，前端重新拉取会话 entry。

### 9.2 事件目录（M4 实际字段）

| type | data 关键字段 |
|---|---|
| `run.started` | `runId, modelProfileId, modelName, maxTurns, startedAt` |
| `turn.started` | `turn` |
| `message.started` | `role`（固定 assistant） |
| `message.delta` | `kind=text` + `text`；`kind=thinking` + `thinking`；`kind=toolCall` + `index, toolCallId, toolName, fragment` |
| `message.completed` | `entryId, stopReason, usage` |
| `tool.started` | `callId, toolName, arguments` |
| `tool.completed` | `callId, toolName, isError, content（≤4000 字符）, details` |
| `run.completed` | `runId, status, stopReason, turns, toolCalls, usage, durationMs` |
| `run.failed` | 同 `run.completed`，并带 `errorMessage` |
| `approval.requested` | `approvalId, callId, toolName, riskLevel, arguments（脱敏）, expiresAt` |
| `approval.resolved` | `approvalId, decision, decidedBy, reason` |
| `compaction.started` | `mode（AUTO/OVERFLOW）, tokensBefore, availableTokens, reason` |
| `compaction.completed` | `mode, tokensBefore, tokensAfter, savedTokens, prunedOnly, note` |
| `replay.gap` | `lastAvailableSeq` |
| `heartbeat` | `at` |

M8 追加：`subagent.*`。

### 9.3 连接管理

- `POST /sessions/{id}/messages/stream` 返回 `text/event-stream`；`SseEmitter(timeout=0)`；
- 订阅在控制器线程同步完成：先重放缓冲，再注册监听器，避免重放与实时之间丢事件；
- 每 15 秒发心跳；写失败只关闭该连接，不反向影响 run；
- 完成/失败事件发送后异步完成 emitter；
- 当前 `SseEmitter.send` 在订阅线程同步执行，没有独立发送队列；慢客户端会阻塞该 run
  的事件广播，v0.2 再评估有界队列与超限断开；
- 断线不等于取消 run，用户可调用 abort 或通过 `GET /runs/{runId}/events` 重连。

## 10. 失败与重试

| 场景 | 行为 |
|---|---|
| LLM 429/5xx/408/网络或连接超时，且尚未输出任何 delta | adapter 内最多重试 2 次（共 3 次请求），短退避 150ms × 第几次重试 |
| 已输出部分 delta 后失败 | 不重试，结束为 FAILED，已输出内容保留 |
| context_length_exceeded | M7 压缩一次后重试；仍失败则 FAILED |
| 工具超时/失败 | 生成 `isError=true` 结果，继续循环 |
| 审批超时（M6） | 按 DENY 处理，继续循环 |
| 持久化失败 | 立即终止 run，标记 FAILED，记录高优先级日志 |
| 插件工具抛异常 | 转为 `isError=true` 结果；插件计数失败，不重试 |
| 子代理失败/超时 | 返回结构化结果；父 run 继续 |

禁止双层重试：application 不重试 LLM 调用，只有 adapter 做传输级重试。

### 10.1 LLM adapter 的实现边界

- **惰性连接**：`stream()` 只做密钥检查、请求映射与 URI 拼接，首次 `hasNext()` 才发请求；
  测试可在不触网的情况下取消。
- **重试判定**：只对可重试错误码（408/429/5xx/网络/连接超时）在“首个 delta 之前”重试；
  401/403、400 非上下文溢出、畸形 SSE 一律立即失败。
- **总超时**：`LlmCallContext.timeout` 覆盖建连与读取全过程；每次读行前检查 deadline，
  不是仅依赖 HttpClient 的单次请求超时。
- **取消**：`cancel()` 关闭响应体，阻塞读抛 `IOException`；流已取消时 `hasNext()` 返回 false，
  不把取消伪装成网络错误，也不触发重试。
- **协议收尾**：收到 `[DONE]` 或 EOF 都调用解析器 `finish()`；空流、非法 JSON、
  声明 `tool_calls` 却没有工具调用、工具缺少 id/name、参数不是 JSON 对象均报
  `MALFORMED_RESPONSE`。
- **usage 归一**：provider 未返回 `total_tokens` 时用 input + output 补齐；
  `prompt_tokens_details.cached_tokens` 与 `prompt_cache_hit_tokens` 取较大值。
- **密钥**：只在发送瞬间读取环境变量并放入 `Authorization`；缺失时返回
  `MISSING_API_KEY`，不发起网络请求；错误信息不包含请求头与密钥。

## 11. 启动恢复

M4 采用**惰性自愈**，不做启动时全表扫描：

1. 用户向会话发消息时，若数据库状态为 `RUNNING/WAITING_APPROVAL` 而进程内注册表
   没有活跃 run，说明上一次进程已崩溃：直接把会话置回 `IDLE`，再开始新 run；
2. 悬空 toolCall 由新的 run 正常投影为历史消息，不再补写结果——M4 没有审批状态，
   悬空结果不会阻塞会话；
3. 事件缓冲只存在内存，进程重启后重连返回 404 `RUN_NOT_FOUND`，前端以 entry 为准。

M6 加入审批、M8 加入子代理后，再补充“补写 RUN_SUMMARY / 过期审批”等恢复步骤；
多实例部署需要持久化 run 租约，v0.1 不支持。

## 12. 并发与资源

| 资源 | 当前值 | 控制方式 |
|---|---|---|
| 活跃 run | 默认 4（`pi.agent.max-concurrent-runs`） | 全局 `Semaphore`；满额返回 429 `AGENT_BUSY` |
| 每会话活跃 run | 1 | `ActiveRunRegistry` 原子预留 |
| 工具调用 | 每 run 默认 100 | `RunBudget.maxToolCalls` |
| LLM 连接 | 每 run 1 条 | run 内串行 |
| SSE 重放缓冲 | 500 条/run | `ArrayDeque` 环形淘汰 |
| 事件通道保留 | 30 分钟 / 最多 300 个 | 定期 prune，淘汰已关闭通道 |
| 线程 | 虚拟线程 | Agent Loop、LLM、工具、SSE 任务 |

`max-turns` 默认 20、`max-duration` 默认 15 分钟，均冻结在 run 的 `RunBudget` 中，
运行中修改配置不影响已开始的 run。

## 13. 可观测性

- M4 记录 run 收尾失败、恢复失败等关键日志（`System.Logger`），正常链路不刷日志；
- 事件流本身包含排查所需的 turn、toolCall、usage 与终止原因，这是当前主要观测面；
- 密钥、Authorization、完整文件内容不进入事件与日志；
- 结构化字段日志、`/health` 与指标在 M9 打磨阶段补充；v0.1 不引入 Prometheus/OpenTelemetry。

## 14. 相关文档

- [领域模型](02-domain-model.md)
- [API 与 CLI](05-api-and-cli.md)
- [插件 SDK](06-plugin-sdk.md)
