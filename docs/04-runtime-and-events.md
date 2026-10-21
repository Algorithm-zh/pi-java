# 04 运行时与事件

> 状态：M0 冻结；M3 已完成只读工具与路径沙箱，Agent Loop、SSE、审批分别在 M4/M6 落地。
> 运行时由 application 层编排，domain 只提供状态机与规则；
> 所有外部等待（LLM、工具、审批）都不持有数据库事务。

## 1. 运行时组件

```text
trigger.web / trigger.cli
        │
        ▼
SendMessageUseCase ──► RunLifecycle ──► RunRegistry（单 session 单 run）
        │                  │
        │                  ├──► ContextAssemblyService ──► SessionContextProjector
        │                  │                              ├──► CompactionPlanner
        │                  │                              └──► SkillPromptFormatter
        │                  ├──► TurnExecutor ──► LlmGateway ──► provider adapter
        │                  └──► ToolCallCoordinator
        │                         ├──► ToolAccessPolicy / ApprovalPort
        │                         ├──► PathSandbox / RealPathResolver
        │                         ├──► ToolInvoker（内置/插件）
        │                         └──► SessionEntryStore（短事务追加）
        ▼
RunEventPublisher ──► SSE hub / CLI renderer
```

职责边界：

| 组件 | 只做什么 | 不做什么 |
|---|---|---|
| `RunLifecycle` | 创建/结束 run、状态迁移、锁与清理 | 调用 LLM、执行工具 |
| `TurnExecutor` | 一次 LLM 请求、流式增量、结束判定 | 审批、路径校验 |
| `ToolCallCoordinator` | 工具预检、审批、执行、结果回写 | 决定模型下一步 |
| `ContextAssemblyService` | 投影、压缩、system prompt | 持久化 entry 细节 |
| `RunEventPublisher` | 事件序列号、缓冲、广播 | 业务判断 |

## 2. 一次 Run 的生命周期

### 2.1 启动

```text
1. 校验会话存在、未归档、无活跃 run（RunRegistry.createIfAbsent）
2. 获取会话短锁，追加用户 MESSAGE entry，更新 leaf/version
3. 创建 AgentRun(status=RUNNING)，发布 run.started
4. 读取分支 entry，投影上下文；超预算则先压缩
5. 组装 system prompt（身份、环境、工具、Skills、AGENTS.md、插件 section）
6. 进入 turn 循环
```

会话锁只在“追加 entry / 移动 leaf / 状态变更”时短暂持有，**不覆盖整个 run**；
单 session 单 run 由 `RunRegistry` 保证，entry 顺序由锁 + `(session_id, seq)` 唯一键保证。

### 2.2 Turn 循环

```text
turnIndex = 0
while true:
    ensure not cancelled and budget not exhausted
    publish turn.started
    stream = LlmGateway.stream(request)
    for delta in stream:
        publish message.delta / toolCall delta
    assistant = stream.response()
    append assistant MESSAGE entry
    publish message.completed

    if assistant.stopReason == ERROR/ABORTED:
        finish(ERROR or ABORTED); break
    if assistant has no toolCall:
        finish(STOP or LENGTH); break
    if turnIndex + 1 >= maxTurns:
        finish(MAX_STEPS); break

    results = ToolCallCoordinator.executeAll(assistant.toolCalls)
    append toolResult entries（与 toolCall 同分支、顺序稳定）
    publish tool.completed
    turnIndex++
```

关键点：

- assistant 消息与 toolResult 分别追加，不合并成一条“大消息”；
- toolResult 必须紧跟对应 toolCall 所在分支，不允许先切分支再写结果；
- 模型返回 `TOOL_USE` 但没有 toolCall，按 `ERROR` 收尾并记录原因；
- 模型返回普通文本但要求 follow-up（插件注入）时，由 `TurnPolicy` 决定是否继续，v0.1 最多一轮 follow-up。

### 2.3 收尾

无论成功、失败、取消，统一走 `finish`：

```text
1. 若还有未完成的 toolCall，补写 isError=true 的 toolResult
2. 追加 RUN_SUMMARY entry（stopReason、usage、turns、toolCalls、duration）
3. 更新 session.status=IDLE
4. 从 RunRegistry 移除，释放审批等待者
5. 发布 run.completed 或 run.failed
6. 关闭 SSE emitter，清理重放缓冲（延迟到断开或过期）
```

持久化失败是唯一例外：直接终止进程内 run，记录高优先级日志，session 在下次启动时由恢复流程修复。

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

```text
RUNNING ──need approval──► WAITING_APPROVAL ──resolved──► RUNNING
   │
   ├──need compaction──► COMPACTING ──done──► RUNNING
   │
   ├──STOP/LENGTH/MAX_STEPS──► COMPLETED
   ├──ERROR──────────────────► FAILED
   └──ABORT/TIMEOUT──────────► ABORTED
```

`COMPACTING` 是 Run 状态，不是会话状态；压缩期间会话仍为 `RUNNING`。
终态不可迁移；重复的取消/完成请求按幂等处理并返回当前状态。

## 4. 工具管线

### 4.1 顺序

M3 已实现的部分（工具可直接通过 `ToolInvoker` 调用，尚未接入 Agent Loop）：

```text
ToolInvocation(callId, tool, arguments, sandbox)
  -> ToolRegistry.require 取 ToolDescriptor（未注册抛 TOOL_NOT_FOUND）
  -> ToolAccessPolicy.resolve（workspace > user > 风险默认；只读查询，不弹审批）
  -> 具体 handler：ToolArgumentGuard 拒绝未知参数并校验必填/上限
  -> RealPathResolver：PathSandbox 词法判定 + toRealPath 物理复核
  -> BuiltInToolInvoker：虚拟线程执行、按 descriptor 超时、异常包装
  -> ToolResult（失败为 isError=true，不向调用方抛业务异常）
```

M4/M5/M6 将补齐的完整管线：

```text
ToolCallBlock
  -> run 开始时的 ToolCatalog 快照取 ToolDescriptor
  -> 插件 ToolInterceptor（只允许 allow/deny，不允许改参数；M5）
  -> ToolAccessPolicy
       ALLOW -> 继续
       ASK   -> 创建 ApprovalRequest，等待决策（M6）
       DENY  -> 生成 isError 结果，不写 APPROVAL（没有人工决策）
  -> PathSandbox + RealPathResolver（文件类工具）
  -> ToolInvoker（超时、取消、输出截断）
  -> 插件 ToolResultObserver（只读观察；M5）
  -> 追加 toolResult entry，发布事件（M4）
```

### 4.2 并行策略

- `ExecutionMode` 已区分 `PARALLEL / SEQUENTIAL`；`ToolExecutionPlanner` 与分批执行在 M4
  随 Agent Loop 实现；
- 当前 `BuiltInToolInvoker` 的每次调用在独立虚拟线程上执行并等待超时，单个调用不阻塞平台线程；
- 计划：默认工具并行度 4；需要人工审批的调用按模型给出顺序逐个询问；
- 计划：批内任一调用被取消，取消信号广播到该批所有工具；
- 计划：工具结果按原 toolCall 顺序写 entry，即使完成顺序不同。

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

后续工具的计划上限：

| 工具 | 默认超时 | 输出限制 |
|---|---|---|
| `write` / `edit`（M6） | 10s | 结果摘要 8 KB |
| `bash`（M6） | 120s（可被策略覆盖） | 64 KB；完整输出写临时文件并返回路径 |
| `subagent`（M8） | 300s | 只返回摘要 + 子会话引用 |

超时视为工具失败：`BuiltInToolInvoker` 取消任务并返回 `isError=true`、
`details.errorCode=TOOL_TIMEOUT` 的结果，模型可据此调整，不终止整个 run。
线程中断返回 `CANCELLED`；handler 抛出的领域异常保留原错误码。

### 4.4 审批（M6 实现）

```text
create PENDING -> publish approval.required
    -> 用户 APPROVE -> APPROVED -> 执行
    -> 用户 DENY    -> DENIED   -> toolResult(isError=true)
    -> 30 分钟超时 -> EXPIRED  -> 按 DENY 处理
    -> run 取消/连接关闭 -> CANCELLED -> 按 DENY 处理
```

- 审批请求落库前脱敏：密钥、Authorization、疑似 token、超长文本；
- `args_digest` 防止 UI 展示参数与实际执行参数漂移；
- 决策接口只接受 PENDING；重复决策返回冲突，不做静默覆盖；
- 决策更新 `pi_approval_request` 与追加 `APPROVAL` entry 在同一短事务内；
- `approvalMode=SESSION` 的允许只对当前会话当前 run 的后续相同工具生效（参数仍重新校验），
  不跨会话、不跨子代理；`ALWAYS` 写入策略表，需显式管理。

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

当前已实现：

- LLM 流：`LlmStream.cancel()/close()` 关闭 HTTP body，迭代立即结束；
- 内置工具：`BuiltInToolInvoker` 超时后 `Future.cancel(true)`；等待线程被中断时
  返回 `CANCELLED` 结果并恢复中断标记。

M4/M6/M8 将补齐贯穿式取消：

- Agent Loop 的 `CancellationToken` 广播到当前 turn 的 LLM、工具与审批（M4/M6）；
- `bash` 先 `destroy()`，5 秒后 `destroyForcibly()`，并清理子进程树（M6）；
- 审批：唤醒等待者并以 CANCELLED 结束（M6）；
- 子代理：父取消级联子 run（M8）；
- 收尾：补写中断结果与 `RUN_SUMMARY(stopReason=ABORTED)`（M4）。

取消是幂等的；已进入终态的 run 忽略取消请求。

## 6. 上下文压缩

### 6.1 触发

```text
estimatedTokens > contextWindow - reserveTokens   // 默认 reserve = 16384
或用户手动 /compact
或 provider 返回 context_length_exceeded（仅重试一次）
```

### 6.2 规划

1. 从最新 entry 向前累计，保留 `keepRecentTokens`（默认 20000）；
2. 切点只允许 user/assistant/custom message，**绝不切 toolResult**；
3. 若单个 turn 本身就超过预算，产生 split-turn：把 turn 前缀单独摘要；
4. 已存在压缩 entry 时，将旧摘要作为“前情”参与新摘要；
5. 生成结构化摘要：Goal / Constraints / Progress / Decisions / Next Steps / Critical Context，
   并附带 read-files / modified-files；
6. 追加 `COMPACTION` entry（summary、firstKeptEntryId、tokensBefore、details）。

### 6.3 收敛保证

- 摘要请求自身必须小于被遮蔽 token；否则缩小摘要预算；
- 摘要后重新估算；若不收敛，降级为“只剪枝 + 保留尾部”，
  仍追加 `COMPACTION` entry（`details.prunedOnly=true`），保证 run 能继续；
- 一次 run 内自动压缩最多触发一次；手动 `/compact` 可再次触发。

## 7. System Prompt 与 Skills

system prompt 按固定顺序组装，任何 section 都有长度上限：

1. 身份与行为约束（简洁、先读后写、不编造工具结果）；
2. 环境：cwd、操作系统、当前时间、模型标识；
3. 工具目录与风险摘要（不含完整 schema，schema 走工具参数）；
4. Skills 目录：仅 `name + description`；
5. `AGENTS.md`：v0.1 只读 cwd 根目录，上限 32 KB；
6. 插件 `PromptSection`（按注册顺序，总上限 16 KB）；
7. 压缩/会话提示（如有）。

Skills 的正文不进入系统提示；agent 通过 `read` 读取 `SKILL.md`，或用户使用
`/skill:name args` 将正文作为一条用户消息注入。Skill 目录缓存 5 分钟，并校验 mtime。

## 8. 子代理

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
  "data": {"delta": "正在读取"}
}
```

- `seq` 在每个 run 内单调递增；心跳使用 `seq=0`，不进入重放缓冲；
- 每个 run 内存保留最近 500 条非心跳事件；
- `Last-Event-ID = runId:seq`：命中则回放 `seq+1` 起的事件；未命中或超出缓冲，
  发送 `replay.gap`，前端重新拉取会话 entry。

### 9.2 事件目录

| type | data 关键字段 |
|---|---|
| `run.started` | `runId, sessionId, model` |
| `turn.started` | `turnIndex` |
| `message.started` | `messageId, role` |
| `message.delta` | `messageId, delta`（文本或 thinking） |
| `message.completed` | `messageId, usage, stopReason` |
| `tool.started` | `toolCallId, toolName, argumentsSummary` |
| `tool.updated` | `toolCallId, partialOutput` |
| `tool.completed` | `toolCallId, isError, durationMs, outputSummary` |
| `approval.required` | `approvalId, toolCallId, toolName, risk, argsRedacted, expiresAt` |
| `approval.resolved` | `approvalId, decision, decidedBy` |
| `compaction.started` | `tokensBefore` |
| `compaction.completed` | `tokensAfter, firstKeptEntryId` |
| `subagent.started` | `childSessionId, mode` |
| `subagent.completed` | `childSessionId, stopReason, usage` |
| `run.completed` | `stopReason, usage, turns, durationMs` |
| `run.failed` | `errorCode, message` |
| `replay.gap` | `lastAvailableSeq` |
| `heartbeat` | `at` |

### 9.3 连接管理

- `POST /sessions/{id}/messages/stream` 返回 `text/event-stream`；`SseEmitter(timeout=0)`；
- 每 15 秒发心跳；写失败立即移除 emitter，不反向影响 run；
- 完成/失败后发送终态事件并关闭连接；
- 慢消费者：发送队列超过 1000 条时断开并让前端走重连/重拉，避免拖垮进程；
- 断线不等于取消 run，用户可调用 abort 或等待结束。

## 10. 失败与重试

| 场景 | 行为 |
|---|---|
| LLM 429/5xx/408/网络或连接超时，且尚未输出任何 delta | adapter 内最多重试 2 次（共 3 次请求），短退避 150ms × 第几次重试 |
| 已输出部分 delta 后失败 | 不重试，结束为 FAILED，已输出内容保留 |
| context_length_exceeded | 压缩一次后重试；仍失败则 FAILED |
| 工具超时/失败 | 生成 `isError=true` 结果，继续循环 |
| 审批超时 | 按 DENY 处理，继续循环 |
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

应用启动时扫描：

1. `status=RUNNING/WAITING_APPROVAL` 但没有活跃 run 的会话：
   - 为悬空 toolCall 补写 `isError=true` 的 toolResult；
   - 追加 `RUN_SUMMARY(stopReason=ERROR, errorMessage="进程重启恢复")`；
   - 会话置为 `IDLE`；
2. `PENDING` 且已过期的审批请求：置 `EXPIRED`，追加 `APPROVAL` entry；
3. 插件状态为 `ACTIVE` 但 jar 缺失/校验失败：置 `FAILED`，记录 `last_error`；
4. 清理临时输出文件与残留子进程（尽力而为，记录失败）。

恢复流程必须幂等：重复启动不会产生重复的 run summary。

## 12. 并发与资源

| 资源 | 默认值 | 控制方式 |
|---|---|---|
| 活跃 run | 4 | 全局信号量；超出返回 429/忙碌错误 |
| 工具并行 | 4 | 每批信号量 |
| LLM 连接 | 每 run 1 条 | run 内串行 |
| SSE 重放缓冲 | 500 条/run | 环形队列 |
| 事件队列 | 1000 条/连接 | 超限断开 |
| 线程 | 虚拟线程 | LLM/工具/SSE 写任务 |

## 13. 可观测性

- 结构化日志固定字段：`sessionId/runId/turn/toolCallId/toolName/durationMs/outcome`；
- 参数只记录摘要与 digest，不记录密钥、Authorization、完整文件内容；
- `/health` 返回数据库、Flyway、插件快照状态；
- v0.1 不引入 Prometheus/OpenTelemetry，避免范围膨胀；日志与事件已包含排障所需链路。

## 14. 相关文档

- [领域模型](02-domain-model.md)
- [API 与 CLI](05-api-and-cli.md)
- [插件 SDK](06-plugin-sdk.md)
