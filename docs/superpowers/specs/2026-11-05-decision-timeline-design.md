# M22 设计：决策时间线与任意检查点回退

> 日期：2026-11-05
> 状态：设计待评审
> 范围：会话时间线投影、任意检查点回退、回退预览、回溯简报、检查点保活

## 1. 背景

截止 M21，pi-java 已经能把一次 run 的工作区状态记录成 Git 检查点，并在会话日志里
留下逐轮 diff；用户也可以撤销最近一次 run。但这套能力仍然以“对话 + 改动面板”为
中心：

- 用户看到的是消息流，真正重要的决定（计划、审批、验证、diff）散落在不同位置；
- 撤销只覆盖最近一次 run，想回到更早的决策只能手动翻历史；
- 撤销之后对话直接退回去，agent 不记得刚才试过什么，容易再次走进同一条死路。

DeepSeek 的 super agent 把重点放在“异步跑更多任务”。pi-java 选择另一个方向：
**让人类随时看懂、质疑并改写 agent 的决策路径**。不是更自动，而是更敢让它动手。

## 2. 目标

- 用户能在当前分支上选择任意一次 run 的检查点，把工作区与对话一起退回去，并从
  那里继续；
- 时间线先呈现“做过什么决定”，再呈现聊天细节：意图、计划、审批、验证、改动；
- 回退前可以预览将丢弃的决策与将还原的文件；有冲突时一个文件都不写；
- 回退后追加结构化的“回溯简报”，让新分支知道哪些路径已被否决或失败；
- 检查点在常规 `git gc` 之后仍然可用，直到会话被删除。

### 非目标

- 不做分支对比、切回旧分支或并排比较结果（留作后续里程碑）；
- 不自动提交、不移动 HEAD、不创建用户可见分支；
- 不做跨会话时间线；
- 不做异步多任务编排；
- M22 不修改 CLI；`/timeline` 与 `/rewind` 作为后续增量；
- 不物理清理回退后留下的旧 entry。

## 3. 术语

| 术语 | 含义 |
|---|---|
| 决策点 | 一次 run 对应的可回退节点，由 `GIT_CHECKPOINT` 锚定 |
| 检查点 | run 开始前的工作区快照，包含 `baseLeafId` 与 `baseTreeSha` |
| 目标树 | 回退目标检查点的 `baseTreeSha`，即 run 开始前的工作区状态 |
| 最新树 | 当前分支上最后一棵已知的工作区树，用于判断冲突 |
| 回溯简报 | 回退后追加的 `BRANCH_SUMMARY`，记录被放弃的决策与文件 |
| 未采用分支 | 回退后不再位于当前分支、但仍留在 entry 日志里的旧路径 |

## 4. 用户体验

### 4.1 入口与布局

- 桌面端：聊天区右侧增加一条窄的“决策脊”，每个决策点是一个时间刻度；点击刻度
  或顶部按钮打开时间线面板。面板默认占右侧约 360px，可折叠，不遮挡输入框。
- 移动端：时间线退化为底部抽屉，一次展示一个决策点的摘要，展开后看证据。
- 时间线按时间从上往下排列，不随消息滚动；当前状态用“现在”标记。
- 没有 Git 的会话仍然显示时间线，但所有节点不可回退，并给出原因。

### 4.2 节点内容

每个决策点默认只显示一行摘要：

```text
14:32  修复登录后空间没有自动恢复                 计划 3/5 · 审批 2（拒绝 1）· 验证失败 · 4 文件 +86 −12
```

展开后显示证据：

- 用户意图：触发这次 run 的消息，过长截断，可展开原文；
- 计划：该节点结束时的待办快照（完成/进行中/未开始）；
- 审批：工具名、批准或拒绝、审批意见；
- 验证：命令、通过/失败、退出码、输出摘要；
- 改动：逐轮文件列表和统一 diff，复用现有改动面板的渲染；
- 运行结果：`RunSummaryPayload` 的停止原因、轮数与工具调用数。

普通助手文本不进入摘要行，只在展开后按需显示；失败、拒绝与冲突使用图标加文字，
不只靠颜色区分。

### 4.3 回退流程

1. 用户点击某个决策点的“回到这次运行之前”；
2. 前端请求 dry-run，打开预览层：
   - 将丢弃的对话轮数与决策数；
   - 将还原的文件列表；
   - 冲突文件与原因（有冲突时确认按钮禁用）；
   - 将写入回溯简报的摘要预览；
3. 用户确认后执行回退；服务端重新校验一次冲突，避免预览与执行之间工作区变化；
4. 成功后前端刷新当前分支、时间线和改动面板，回到检查点位置，输入框聚焦；
5. 失败或冲突时保留预览层，逐条列出冲突路径，不修改工作区。

回退当前最新的决策点，等价于撤销最近一次 run；界面上使用同一入口，不额外暴露
另一套“撤销”按钮。现有 `POST /sessions/{id}/undo` 保持原有语义不变，时间线回退
是新增用例，不替换旧接口。

### 4.4 回溯简报

回退成功后，在检查点位置插入一张“回溯简报”卡片，并作为 `BRANCH_SUMMARY` 进入
上下文。卡片可折叠，默认展示三行：

```text
从 14:32 的决策点回退：丢弃 2 轮对话、1 次被拒绝的审批、1 次失败的验证。
已还原 4 个文件；未完成：补登录测试。
继续时不要重复：删除缓存目录（已拒绝）、直接改生产配置（已验证失败）。
```

简报完全由结构生成，不调用 LLM，不包含文件内容或 diff 正文。

### 4.5 空状态与异常

- 还没有任何 run：显示“还没有决策点，发送第一条消息后出现”。
- 不是 Git 仓库或关闭了 Git：时间线仍显示消息与 run 摘要，节点标记“不可回退”。
- 会话运行中：面板可看，回退按钮禁用并提示“等待当前运行结束”。
- 已归档会话：只读，回退禁用。
- 冲突：预览层保持打开，逐条显示“目标之后又被修改”的文件。

## 5. 架构与组件

| 层 | 职责 |
|---|---|
| 领域 | 复用现有 `GitCheckpointPayload`、`GitChangeSetPayload`、`PlanPayload`、`ApprovalPayload`、`VerificationPayload`、`RunSummaryPayload`、`BranchSummaryPayload`；不新增 entry 类型 |
| 应用 | `SessionTimelineService` 只读投影；`SessionRewindService` 负责预览与执行；`RewindBriefBuilder` 生成确定性摘要 |
| 基础设施 | 扩展 `WorkspaceGit`：只读还原计划、检查点保活 ref；`CliGitWorkspace` 实现，`DisabledWorkspaceGit` 降级 |
| 触发层 | 新增 `SessionTimelineController`，提供时间线与回退接口及 DTO |
| Web | 新增 `timeline.js`、`timeline.css` 与对应 DOM；`api.js` 增加两个方法 |

数据流：

```text
session.leafEntryId()
        │
        ▼
SessionEntryStore.loadBranch ──► SessionTimelineService ──► GET /timeline
                                          │
                                          ▼
                              节点：意图/计划/审批/验证/改动

rewind(checkpointEntryId, dryRun)
        │
        ├─ WorkspaceGit.planRestore(target, latest) ──► 预览
        └─ WorkspaceGit.restore(target, latest) ──► 还原
                 │
                 ▼
        session.rewind(baseLeafId) + 追加简报 ──► 新分支
```

## 6. 数据与算法

### 6.1 时间线投影

`SessionTimelineService.timeline(sessionId)` 只读当前分支：

1. 读取会话，使用 `session.leafEntryId()` 加载分支；空分支返回空时间线；
2. 按 `GIT_CHECKPOINT` 切分决策点；检查点之后、下一个检查点之前的 entry 属于该节点；
3. 触发消息取检查点之前最近的一条用户 `MessagePayload`（正常顺序是用户消息紧挨在
   检查点之前）；
4. 节点内的计划取该节点结束前最近一次 `PlanPayload` 快照，供显示“当时的计划进度”；
5. 审批、验证、逐轮改动、run 摘要按 `runId` 归入节点；
6. 遇到 `BranchSummaryPayload` 且 `details.kind = "rewind"` 时插入一条只读的
   `REWIND` 节点，位于它在分支中的实际位置；
7. `current` 标记 = 当前叶子落在其区间内的 `RUN` 节点；叶子是 `REWIND` 节点或空
   会话时没有当前 `RUN` 节点。

定义：

- `available` = 当前分支上至少有一个 `GIT_CHECKPOINT`；
- `rewindable` = 该节点有检查点、会话处于 `IDLE` 且未归档；运行中或归档时所有节点
  都不可回退；
- `currentCheckpointEntryId` 在没有当前 `RUN` 节点时为 `null`。

如果会话没有任何 `GIT_CHECKPOINT`，退化为按 `RunSummaryPayload` 的 `runId` 分组，
节点 `rewindable=false`，`available=false`。

节点 DTO 至少包含：

```json
{
  "type": "RUN",
  "checkpointEntryId": "01J...",
  "runId": "01J...",
  "at": "2026-11-05T14:32:10Z",
  "current": false,
  "rewindable": true,
  "intent": "修复登录后空间没有自动恢复",
  "plan": {"total": 5, "done": 3},
  "approvals": {"total": 2, "denied": 1},
  "verification": {"total": 1, "failed": 1},
  "changes": {"turns": 2, "files": 4, "additions": 86, "deletions": 12},
  "stopReason": "COMPLETED"
}
```

`REWIND` 节点只包含 `at`、`current`、`brief` 与 `discarded` 统计，不提供回退。

### 6.2 回退目标与树

设目标检查点为 `C`：

- **目标树** = `C.baseTreeSha`；
- **最新树**：从 `C` 所在的 entry 之后沿当前分支向后扫描，遇到 `GIT_CHANGES` 取
  `treeSha`，遇到后续 `GIT_CHECKPOINT` 取 `baseTreeSha`，最后一个值即最新树；
- 若最新树等于目标树，说明这段路径没有文件变化，只回退对话；
- 冲突判定沿用现有 `restore` 语义：对目标树与最新树之间变化的每个路径，比较当前
  工作区 blob 与最新树 blob；任一不同即冲突，整体放弃，不写任何文件；
- 还原成功后，`session.rewind(C.baseLeafId)`；`baseLeafId` 为 null 时沿用现有撤销
  语义，先 `deleteAll` 再回到空会话；
- 旧 entry 不删除；它们只是不在当前分支上，后续可以用于“未采用分支”浏览。

### 6.3 回溯简报

`RewindBriefBuilder` 只使用结构数据，输入：

- 被丢弃的入口：用户消息、助手轮次、审批、验证、计划；
- 被拒绝的审批：工具名与理由；
- 失败的验证：命令与退出码；
- 未完成的计划项；
- 被还原的文件路径；
- 可选用户备注。

输出一段不超过 400 字的摘要，以及结构化 `details`：

```json
{
  "kind": "rewind",
  "checkpointEntryId": "01J...",
  "runId": "01J...",
  "discardedEntries": 12,
  "discardedRuns": 2,
  "restoredFiles": 4,
  "deniedApprovals": 1,
  "failedVerifications": 1,
  "unfinishedPlanItems": 1
}
```

`discardedEntries` 是原始 entry 数；简报正文里的“2 轮对话”指被丢弃的用户消息数，
两者不混用。`note` 最多 200 字，服务端 trim 后写入；前端用 `textContent` 展示。

摘要作为 `BranchSummaryPayload.summary`，`fromEntryId` 为回退前的叶子。追加后会话
叶子指向简报 entry；它作为 `BranchSummaryMessage` 进入后续上下文，token 估算按
摘要文本计算。

### 6.4 检查点保活

现有树对象没有任何 ref 指向，`git gc` 后旧检查点可能失效。M22 给每个检查点建内部
ref：

```text
1. git commit-tree <treeSha> -m "pi checkpoint <runId>"   生成内部 commit
2. git update-ref refs/pi/checkpoints/<sessionId>/<runId> <commit>
```

- 不移动 HEAD、不创建可见分支、不写入用户索引；
- 失败只记 WARNING，不影响 run；
- 配置项 `pi.git.pin-checkpoints` 默认 `true`，关闭后回到当前行为；
- 会话被永久删除（不是归档）时，尽力删除该会话的全部
  `refs/pi/checkpoints/<sessionId>/` ref；归档保留；
- ADR 0005 的“不产生提交、不移动 ref”边界需要同步更新，说明这是内部保活 ref。

## 7. HTTP 契约

### 7.1 查询时间线

```http
GET /api/v1/sessions/{sessionId}/timeline
```

响应：

```json
{
  "available": true,
  "currentCheckpointEntryId": "01J...",
  "nodes": [
    {
      "type": "RUN",
      "checkpointEntryId": "01J...",
      "runId": "01J...",
      "at": "2026-11-05T14:32:10Z",
      "current": true,
      "rewindable": true,
      "intent": "修复登录后空间没有自动恢复",
      "plan": {"total": 5, "done": 3},
      "approvals": {"total": 2, "denied": 1},
      "verification": {"total": 1, "failed": 1},
      "changes": {"turns": 2, "files": 4, "additions": 86, "deletions": 12},
      "stopReason": "COMPLETED"
    }
  ]
}
```

- 非 Git 会话返回 `available=false`，`nodes` 仍包含 run 节点，`rewindable=false`；
- 不访问磁盘，重启后仍可查询；
- 租户隔离沿用会话查询边界，跨空间返回 404。

### 7.2 回退

```http
POST /api/v1/sessions/{sessionId}/rewind
Content-Type: application/json

{"checkpointEntryId": "01J...", "dryRun": true, "note": "换一种修法"}
```

预览响应：

```json
{
  "dryRun": true,
  "rewound": false,
  "restorable": true,
  "files": ["src/main/java/.../AuthService.java"],
  "conflicts": [],
  "discardedEntries": 12,
  "discardedRuns": 2,
  "brief": "从 14:32 的决策点回退：..."
}
```

执行成功：

```json
{
  "dryRun": false,
  "rewound": true,
  "restored": ["src/main/java/.../AuthService.java"],
  "conflicts": [],
  "briefEntryId": "01J...",
  "discardedEntries": 12,
  "discardedRuns": 2
}
```

有冲突时 HTTP 仍为 200：

```json
{
  "dryRun": false,
  "rewound": false,
  "restorable": false,
  "restored": [],
  "conflicts": [
    {"path": "src/main/java/.../AuthService.java", "reason": "目标之后工作区又发生了变化，为避免覆盖已跳过"}
  ],
  "discardedEntries": 0,
  "discardedRuns": 0
}
```

错误：

| 场景 | code | HTTP |
|---|---|---|
| 会话不存在 / 不属于当前空间 | `SESSION_NOT_FOUND` | 404 |
| 会话运行中或等待审批 | `INVALID_STATE_TRANSITION` | 409 |
| 会话已归档 | `SESSION_ARCHIVED` | 409 |
| 不是 Git 仓库或关闭 Git | `GIT_UNAVAILABLE` | 409 |
| 检查点不在当前分支 / 已是当前叶子 | `INVALID_BRANCH` | 409 |
| git 执行失败 | `GIT_OPERATION_FAILED` | 500 |

服务端不信任预览结果：执行时重新计算目标树、最新树与冲突。

## 8. Web 交互规范

- 新增 DOM：`#timeline-panel`、`#timeline-list`、`#timeline-preview`、
  `#timeline-brief-template`；时间线卡片与预览层全部用 DOM 构造，禁止 `innerHTML`。
- `timeline.js` 暴露 `initTimeline()`、`refresh()`、`reset()`，由 `session-view.js`
  在会话加载、run 结束和回退完成后调用。
- 状态：`closed | loading | ready | previewing | rewinding | error`；每次切换会话
  清空选中节点与预览。
- 预览层使用 `aria-modal="true"`，确认按钮在冲突或加载时禁用；回退结果通过
  `aria-live="polite"` 提示。
- 桌面与移动端复用同一份节点数据；移动端隐藏次要统计，证据区改为逐段折叠。
- 冲突、拒绝、失败都有图标与文字，不依赖颜色；键盘 `Esc` 关闭面板/预览。

## 9. 降级与安全

- **租户隔离**：时间线与回退都先按当前空间查找会话；跨空间一律 404，不泄露存在性。
- **不覆盖用户改动**：回退沿用现有冲突保护，有冲突整体放弃。
- **不改仓库状态**：不移动 HEAD、不写用户索引、不创建可见分支；保活 ref 位于
  `refs/pi/` 内部命名空间。
- **路径安全**：删除与写回文件仍走现有 `safePath` 校验，拒绝仓库外路径。
- **简报不泄密**：摘要只包含路径、工具名、命令与理由，不包含文件内容、密钥或完整
  验证输出。
- **Git 降级**：快照、保活、还原计划失败只影响时间线与回退，不影响对话与 run。
- **大仓库**：时间线只读数据库；dry-run 需要一次工作区快照，前端显示加载态，避免
  用户重复点击。
- **磁盘增长**：保活 ref 会保留树对象，直到会话删除；M22 不做自动过期，文档明确
  说明清理方式。

## 10. 测试

- 应用层 `SessionTimelineServiceTest`：
  - 多 run 分组、触发消息归属、计划/审批/验证/改动归组；
  - 无 Git 会话退化为 run 节点；空分支；
  - `REWIND` 节点位置与 `current` 标记。
- 应用层 `SessionRewindServiceTest`：
  - 回退到中间检查点：还原文件、回退对话、追加简报；
  - 回退到第一条消息：清空 entry 后简报成为根；
  - dry-run 不写工作区、不追加 entry；
  - 冲突整体放弃；运行中/归档/非法检查点拒绝；
  - 简报内容包含被拒绝审批、失败验证与未完成项；备注被带入；
  - 旧 entry 在其他分支不可见，但不被删除。
- 基础设施：
  - 任意目标树与最新树之间的还原；
  - 保活 ref 创建、`git gc` 后仍可读取树、会话删除后 ref 清理；
  - 关闭 `pin-checkpoints` 时不创建 ref。
- 触发层：两个接口的成功、预览、冲突、错误映射与租户隔离。
- 集成：初始化 → 两次 run → 时间线 → 回退 → 继续运行，验证上下文包含简报。
- 浏览器走查：写文件、拒绝审批、验证失败三条消息 → 回到第二条 → 工作区恢复、
  简报出现、继续对话不重复死路；桌面与移动端布局；无 `innerHTML`。

## 11. 里程碑拆分

| 步骤 | 内容 |
|---|---|
| M22-1 | 应用层时间线投影与 DTO；无 Git 退化 |
| M22-2 | `WorkspaceGit` 还原计划与检查点保活 ref |
| M22-3 | `SessionRewindService`：预览、执行、简报、冲突与状态校验 |
| M22-4 | HTTP 控制器、DTO、租户隔离与错误映射 |
| M22-5 | Web 时间线、预览层、简报卡片与响应式样式 |
| M22-6 | 全量测试、Checkstyle、浏览器走查、文档与本地提交 |

## 12. 风险与备选

| 风险 | 缓解 |
|---|---|
| 树对象被 gc 清理 | 检查点保活 ref，默认开启 |
| 旧 entry 让日志变大 | 不删除是审计需求；后续可做“未采用分支”浏览或清理策略 |
| 简报占用上下文 | 摘要限 400 字，details 结构化；必要时后续做压缩 |
| 时间线 UI 复杂 | 默认只显示摘要行，证据按需展开；移动端抽屉 |
| 预览与执行之间工作区变化 | 执行时重新校验冲突，必要时整体放弃 |
| 大仓库 dry-run 慢 | 只做一次快照与一次 diff-tree；加载态与防重复点击 |

备选方案：只做“任意检查点回退”而不做时间线（范围更小，但同质化）；或进一步做
“分叉对比”（范围更大，留作 M23）。

## 13. 开放问题

- CLI 的 `/timeline` 与 `/rewind` 是否在 M23 跟进：M22 不做，接口与投影保持不变，
  后续直接复用。
- 保活 ref 是否需要按时间自动过期：M22 只做会话删除时清理，避免引入新的保留策略。

## 14. 参考

- [12 Git 检查点与撤销](../12-git.md)
- [ADR 0005：临时索引检查点](../adr/0005-git-checkpoints.md)
- [M13 里程碑记录](../milestones/M13.md)
- [04 运行时与事件](../04-runtime-and-events.md)
- [05 API 与 CLI](../05-api-and-cli.md)
- [11 界面设计](../11-interface-design.md)
