# M25 设计：切回被放弃的分支

> 日期：2026-11-06
> 状态：设计待评审
> 范围：同一会话内的分支切换（对话分支 + 工作区）、切换预览与冲突处理、切换简报、
> 时间线与对比泛化、`/resume` API 与 Web/CLI 入口

## 1. 背景

M22 让用户回到任意检查点，M23 让用户读到被放弃分支做了什么。两件事合起来仍然缺
最后一环：**看完之后，回不去**。

现状有三个硬限制：

- 回退之后，旧分支只能看不能走；用户想“还是按原来的思路继续”时，唯一的办法是
  重新输入一遍旧分支的意图，模型拿不到旧分支的过程；
- 对比只能解释差异，不能改变状态；M23 明确把“切回旧分支”列为非目标；
- `fork` 创建的是只带一条摘要的子会话，不复制 entry，无法承载“带着完整上下文继续
  另一条路”的需求。

M25 做一件事：**让分支成为可以来回走的路，而不只是历史里的墓碑**。切换发生在同一
个会话内，保留完整 entry 与工作区，切过去之后模型看到的是那条分支真实的上下文。

这也是 pi-java 与通用 coding agent 的差异点：多数 agent 只能“撤销”，pi-java 可以
“换一条路继续”，并且换路本身是一次有简报、有预览、可回退的决策。

## 2. 目标

- 在同一会话内切到任意一条已知分支的叶子，恢复该分支的对话上下文与工作区；
- 切换前给出预览：目标分支概况、将改动的工作区路径、冲突列表；
- 切换成功后追加一条 `kind = "resume"` 的分支简报，作为新分支的起点，并把
  “刚离开的那条分支”记录为可再次切回的目标；
- 切换是双向的：切过去之后，用同样的入口可以切回刚才那条分支；
- 时间线增加 `RESUME` 节点，对比同时接受 `rewind` 与 `resume` 简报；
- Web 与 CLI 提供“切到这条分支”入口，复用回退预览的交互范式；
- 不新增数据库迁移；所有新事实都落在 entry payload 的 `details` 里。

### 非目标

- 不做跨会话切换：`fork` 出的子会话、其他会话的分支都不是目标；
- 不做分支合并：切换是移动叶子，不产生 merge 语义，也不自动把两条分支的改动叠加；
- 不做工作区三方合并：任何冲突都整体放弃，不逐文件提示“要不要覆盖”；
- 不做自动切换或基于模型的推荐切换；
- 不做旧分支的删除、归档或配额；
- 不做逐行 diff，对比仍是路径级；
- 不改变 M22 回退的语义与接口，只补充简报字段与保活。

## 3. 术语与核心语义

| 术语 | 含义 |
|---|---|
| 分支切换点 | 当前分支上的一条 `BRANCH_SUMMARY`，`kind` 为 `rewind` 或 `resume` |
| 目标分支 | 切换后所在的分支，切换点是简报的 `fromEntryId` 一侧 |
| 离开的分支 | 切换前所在的分支，即当前叶子所在的一侧 |
| 目标叶子 | `fromEntryId`，目标分支的最后一个 entry |
| 工作区树证据 | 分支叶子对应的工作区快照树 `treeSha`，用于切换时还原文件 |

核心语义：

1. **一个会话、一个叶子、一棵 entry 树**。切换只移动 `leafEntryId`，不复制 entry，
   不创建新会话；两个方向的完整历史都保留在日志里。
2. **回退与切回是同一类动作**。回退的目标是“某次 run 之前”，切回的目标是“某条
   分支的末端”，二者都在移动叶子并追加一条简报。
3. **简报永远指向对侧**。新简报的 `fromEntryId` 是刚离开的叶子，因此任何一次切换
   都可以沿同一条简报原路返回，来回切换是对称的。
4. **对话与工作区一起切换**。切过去之后，模型上下文来自目标分支的真实 entry，
   工作区来自目标分支记录的工作区树；不是把摘要塞进上下文假装切换。
5. **切换本身进入历史**。新简报是目标分支上的一个新节点，下一次时间线、对比和
   切换都能看到“某时刻从另一条分支切换过来”。

## 4. 分支移动规则

### 4.1 回退（已有，M22）

```text
当前分支：… → baseLeaf → run 的 user/tool/… → oldLeaf
回退后：  … → baseLeaf → 回退简报（fromEntryId = oldLeaf）
工作区：  还原到 checkpoint.baseTreeSha
```

### 4.2 切回（新增，M25）

```text
当前分支：… → fork → 当前尾段 → oldLeaf
目标分支：… → fork → 目标尾段 → targetLeaf（= 简报.fromEntryId）
切回后：  … → fork → 目标尾段 → targetLeaf → 切回简报（fromEntryId = oldLeaf）
工作区：  从“当前分支树”还原到“目标分支树”
```

- 分叉点用两条分支的 entry id 最长公共前缀求得，与 M23 口径一致；
- `targetLeaf` 必须与当前叶子分叉，不能是当前分支上的祖先 entry；
- 切回简报追加在 `targetLeaf` 之后，成为新的叶子；目标分支本身没有被改写；
- 旧分支的 entry 全部保留，只是不再位于当前分支。

### 4.3 来回切换

```text
分支 A … → a1 → a2
分支 B … → b1
        ↑ rewind 简报（fromEntryId = a2）
在 B 上切回 A：
  … → a1 → a2 → resume 简报（fromEntryId = b1）
再切回 B：
  … → b1 → resume 简报（fromEntryId = 上一个 resume 简报）
```

每一次切换只跨一步；嵌套切换会留下多个简报，但仍是一条线性可读的当前分支，
不会递归展开成图。对比始终只比较“当前叶子”与“所选简报的另一侧”。

## 5. 数据与载荷

### 5.1 复用 `BranchSummaryPayload`

不新增 entry 类型，继续使用 `BRANCH_SUMMARY`，用 `details.kind` 区分：

| `kind` | 产生时机 | `fromEntryId` |
|---|---|---|
| `rewind` | 回退到检查点 | 回退前的旧叶子 |
| `resume` | 切到另一条分支 | 切换前的旧叶子 |

### 5.2 新增 `details` 字段

| 字段 | 类型 | 说明 |
|---|---|---|
| `fromTreeSha` | string | 离开分支的工作区树；执行切换前的实时快照，失败时回退到记录树 |
| `toTreeSha` | string | 切换完成后工作区所在的树；目标分支树 |
| `leftEntries` | int | 离开分支的尾段 entry 数（分叉点之后） |
| `leftRuns` | int | 离开分支尾段的 run 数 |
| `targetEntries` | int | 目标分支尾段的 entry 数，仅 `resume` 简报 |
| `targetRuns` | int | 目标分支尾段的 run 数，仅 `resume` 简报 |
| `restoredFiles` | int | 计划改动的工作区路径数 |
| `restoredPaths` | string[] | 路径样本，最多 50 条 |
| `branchEntryId` | string | 发起切换的简报 entry，仅 `resume` 简报 |

`rewind` 简报保留现有的 `checkpointEntryId`、`runId`、`discardedEntries`、
`discardedRuns`、`deniedApprovals`、`failedVerifications`、`unfinishedPlanItems`，
新增 `fromTreeSha`、`toTreeSha`。旧数据缺少新字段时按第 6 节回退。

统计口径统一为“分叉点之后的尾段”，此前只统计 `rewind` 丢弃的 entry；两者一致，
不需要迁移历史数据。

## 6. 工作区树证据

### 6.1 为什么需要

切换必须把工作区从“当前分支的状态”还原到“目标分支的状态”。会话里已经有树信息，
但分散在不同 payload 中，且不是每个分支末端都有明确的一棵树。

### 6.2 优先级

目标树按以下顺序取值：

1. 切换点 `details.fromTreeSha`（M25 起写入，最准确）；
2. 目标分支上最后一个 `GIT_CHANGES.treeSha`；
3. 目标分支上最后一个 `GIT_CHECKPOINT.baseTreeSha`；
4. 目标分支上最后一条分支简报的 `details.toTreeSha`；
5. 都没有 → 目标树不可用。

当前树同理，但只扫描当前分支。第 2～4 步封装为 `BranchTrees.treeOf(branch)`，
从叶子向前找第一个可用值。

### 6.3 执行时记录实时快照

执行切换前调用 `git.snapshot(cwd)`，把实时工作区树作为 `fromTreeSha`：

- 快照包含未跟踪但未被忽略的文件（与 M22 回退一致）；
- 若用户在最后一次记录之后手改过工作区，快照会包含这些手改；
- 快照失败时退回当前分支的记录树，保证切换本身不被审计信息拖垮。

冲突判定仍使用记录树：`planRestore(cwd, targetTree, currentTree)` 会比较实时工作区
与 `currentTree`，不一致的路径进入冲突。这样既记录真实状态，又不静默覆盖手改。

### 6.4 保活

切换成功后，尽力为两侧树创建内部 ref（失败只记日志）：

| key | 树 |
|---|---|
| `branch-<离开叶子 id>` | `fromTreeSha` |
| `branch-<目标叶子 id>` | `toTreeSha` |

回退执行时同样保活“被放弃的末端树”，补上 M22 只在 run 起点保活的缺口。
`clearCheckpoints(sessionId)` 已经按会话命名空间清理，删除会话时一并回收。

## 7. 应用服务

### 7.1 新增 `SessionBranchResumeService`

```text
resume(sessionId, branchEntryId, dryRun, note):
  1. 加载会话；归档 → SESSION_ARCHIVED；非 IDLE → INVALID_STATE_TRANSITION
  2. 当前分支 = loadBranch(leaf)
  3. 在分支上定位 branchEntryId，payload 必须是 kind ∈ {rewind, resume}
     的 BranchSummaryPayload，否则 INVALID_BRANCH
  4. targetLeaf = payload.fromEntryId
     目标分支 = loadBranch(targetLeaf)；为空、或 targetLeaf 仍在当前分支上 → INVALID_BRANCH
  5. fork = 两条分支的最长公共前缀
     targetSegment = 目标分支[fork..]
     leftSegment   = 当前分支[fork..]
  6. targetTree = fromTreeSha 或 BranchTrees.treeOf(目标分支)
     currentTree = BranchTrees.treeOf(当前分支)
     任一缺失 → available=false + unavailableReason
  7. plan = git.planRestore(cwd, targetTree, currentTree)
     Git 读取失败 → available=false + unavailableReason（不写任何数据）
  8. 生成简报（BranchResumeBriefBuilder）
     dryRun → 返回预览
  9. plan 存在冲突 → 返回 restorable=false（不改叶子、不追加简报）
 10. 执行：
     - fromTreeSha = git.snapshot(cwd) 的树，失败则 currentTree
     - sessions.update(session.moveLeaf(targetLeaf))
     - summaries.append(resume 简报)，父节点为 targetLeaf
     - git.restore(cwd, targetTree, currentTree)
     - 还原不完整 → 抛 GIT_OPERATION_FAILED，事务回滚
     - 尽力保活两侧树
     - 返回 switched=true
```

约束与 M22 一致：只有空闲会话可以切换；叶子移动、简报追加与工作区写入处于同一
事务；写入阶段失败时回滚数据库，工作区可能留下部分已写文件（已知限制）。

### 7.2 新增 `BranchResumeResult`

| 字段 | 说明 |
|---|---|
| `dryRun` | 是否只是预览 |
| `switched` | 是否已完成切换 |
| `available` | 目标树与当前树是否都可用 |
| `restorable` | 是否存在冲突 |
| `unavailableReason` | 不可用原因，预览时展示 |
| `targetLeafId` | 目标分支叶子 |
| `files` | 计划改动的工作区路径 |
| `restored` | 实际写入/删除的路径 |
| `conflicts` | 冲突列表 |
| `briefEntryId` | 新简报 entry；预览或失败时为 null |
| `leftEntries` / `leftRuns` | 离开分支的尾段统计 |
| `targetEntries` / `targetRuns` | 目标分支的尾段统计 |
| `brief` | 简报正文；预览也返回 |

### 7.3 新增 `BranchResumeBriefBuilder`

确定性生成，不调用 LLM，最多 400 字，例如：

```text
切回另一条分支：离开 2 轮对话、1 个 run；目标分支有 3 轮对话、2 个 run。
已改动 2 个文件（a.java、b.java）。目标分支最后一次意图：修复登录超时。
继续时不要重复：write_file（已拒绝）、mvn test（已验证失败）。备注：先跑测试。
```

规则：

- 离开与目标两侧的轮数、run 数来自尾段扫描，复用 M22 的拒绝/失败判定；
- 目标分支最后一次用户意图取目标尾段最后一条用户消息，截断 80 字；
- 拒绝工具与失败命令取目标尾段的集合，各最多 3 条；
- 备注沿用 200 字上限；
- 同样内容进入模型上下文（`BranchSummaryPayload` → `BranchSummaryMessage`），
  因此切换后的第一轮对话自动带着交接信息。

### 7.4 修改 `SessionRewindService`

- `RewindBriefBuilder` 增加 `fromTreeSha`、`toTreeSha` 参数与 details 字段；
- 执行前捕获实时快照作为 `fromTreeSha`；
- 保活 `branch-<oldLeaf>` 与 `branch-<baseLeaf>`；
- 预览与结果结构不变。

## 8. 时间线与对比

### 8.1 时间线

- `SessionTimeline.NodeKind` 增加 `RESUME`；
- 所有 `kind ∈ {rewind, resume}` 的简报都生成分支节点；
- 分支证据从 `RewindEvidence` 改名为 `BranchEvidence(entryId, brief, leftEntries,
  leftRuns)`；`Node` 的 `rewind` 字段改名为 `branch`；
- `RESUME` 节点的 `current` 标记、`available`、`currentCheckpointEntryId` 语义不变
  （叶子是简报时后者为 null）；
- 分支节点 `rewindable = false`，切换入口使用 `branchEntryId`。

### 8.2 对比

- `SessionBranchComparisonService` 接受两种简报，不再只筛 `rewind`；
- 查询参数改为 `branch`，保留 `rewind` 作为别名；
- 返回字段 `rewindEntryId` 改名为 `branchEntryId`；
- 两侧字段名 `abandoned` / `current` 保留，语义文档化为“简报 `fromEntryId` 一侧”
  与“当前一侧”；摘要措辞从“旧分支”改为“另一条分支”；
- 对比仍然只读、只比较相邻两段。

### 8.3 HTTP 响应字段

时间线分支节点移除 `rewindEntryId`、`discardedEntries`、`discardedRuns`，改为：

| 字段 | 说明 |
|---|---|
| `type` | `RUN` / `REWIND` / `RESUME` |
| `branchEntryId` | 切换点 entry |
| `brief` | 简报正文 |
| `leftEntries` | 离开分支的 entry 数 |
| `leftRuns` | 离开分支的 run 数 |

RUN 节点字段不变。这是 v0.4 的接口字段调整，发布说明中注明。

## 9. HTTP API

```http
POST /api/v1/sessions/{id}/resume
Content-Type: application/json

{ "branchEntryId": "01J...", "dryRun": true, "note": "" }
```

响应（预览）：

```json
{
  "dryRun": true,
  "switched": false,
  "available": true,
  "restorable": false,
  "unavailableReason": "",
  "targetLeafId": "01J...",
  "files": ["src/Login.java", "src/Timeout.java"],
  "restored": [],
  "conflicts": [
    { "path": "src/Login.java", "reason": "本轮之后工作区又发生了变化，为避免覆盖已跳过" }
  ],
  "briefEntryId": null,
  "leftEntries": 5,
  "leftRuns": 2,
  "targetEntries": 8,
  "targetRuns": 3,
  "brief": "切回另一条分支：…"
}
```

状态码：

| 情况 | 状态 |
|---|---|
| 预览、执行、冲突、树不可用 | 200 |
| 简报不在当前分支、目标就是当前分支、目标不是分支简报 | 409 `INVALID_BRANCH` |
| 会话非空闲、已归档 | 409 |
| 会话不存在 | 404 |
| 执行阶段写工作区失败 | 500 `GIT_OPERATION_FAILED` |

时间线接口只增加 `RESUME` 类型与新字段；对比接口参数与字段调整见 8.2。

## 10. Web

### 10.1 时间线

- `REWIND` / `RESUME` 节点统一渲染为分支卡片；
- 卡片事实行改为“离开 N 条记录 · M 次运行”；
- 两个次按钮：
  - “对比两条分支” → 现有对比对话框；
  - “切到这条分支” → 切换预览对话框；
- 节点标题：回退节点“在 HH:mm 回溯”，切回节点“在 HH:mm 切回”；
- 脊柱刻度的分支标记区分两种类型，颜色沿用现有信号色；
- 底部提示补一句“分支节点可以在两条路之间切换”。

### 10.2 切换预览

复用 `#timeline-preview`，增加模式变量 `previewMode = "rewind" | "resume"`：

| 元素 | 回退 | 切换 |
|---|---|---|
| 标题 | 回到「意图」之前 | 切到「时间」的另一条分支 |
| 眉题 | 回退预览 / 存在冲突 / 正在回退 | 切换预览 / 存在冲突 / 正在切换 |
| 事实 | 丢弃 N 条记录 / M 次运行 | 离开 N 条记录 / M 次运行 / 改动 F 个文件 |
| 文件 | “将还原”列表 | “将改动”列表（含删除路径） |
| 冲突 | “回退被整体放弃” | “切换被整体放弃” |
| 确认按钮 | 确认回退 | 确认切换 |
| 成功提示 | 已回退，还原 N 个文件 | 已切到另一条分支，改动 N 个文件 |

- 树不可用时展示 `unavailableReason`，确认按钮禁用；
- 成功后调用 `reloadHandler` 重载会话与时间线，焦点回到输入框；
- 请求超时沿用 15 秒兜底；
- 全部使用 `textContent` 与 DOM 构造，不使用 `innerHTML`。

### 10.3 对比对话框

- 标题与分组改为中性措辞：“另一条分支” / “当前分支”；
- 底部新增“切到另一条分支”按钮，点击关闭对比并打开切换预览；
- 文件分组标签保持“仅另一条分支 / 仅当前分支 / 两边都改过”。

### 10.4 会话中的切换简报

`turn-view.js` 为 `kind = "resume"` 增加专用渲染：

- 正文用简报；
- 次按钮“查看离开的分支”，复用 `data-rewind-from` 的只读历史弹窗；
- 回退简报保持不变。

## 11. CLI

| 命令 | 行为 |
|---|---|
| `/timeline` | 分支节点显示“回退”或“切回”，并给出序号、时间、简报首句、离开计数 |
| `/compare [n]` | 接受 `REWIND` 与 `RESUME` 节点；不带序号取最近一条分支简报 |
| `/resume [n]` | 以该分支节点为目标切换；先预览，再确认；非交互直接拒绝 |
| `/rewind <n>` | 行为不变 |

`/resume` 的预览输出：

```text
将切换 · 离开 5 条记录 / 2 个 run · 目标分支 8 条记录 / 3 个 run · 改动 2 个文件
  切回另一条分支：…（简报）
  - src/Login.java
  ! src/Timeout.java · 本轮之后工作区又发生了变化，为避免覆盖已跳过
```

- 有冲突或树不可用时只展示原因，不接受确认；
- 确认后重新加载会话，打印“已切换 · 改动 N 个文件”；
- `/timeline` 末尾提示增加“/resume <序号> 切到另一条分支”；
- 帮助文本、Slash 补全与 TUI 提示同步更新；
- `/compare` 与 `/resume` 共用节点解析，只接受分支节点。

## 12. 一致性与边界

- 只有空闲会话可以切换；运行中、等待审批时拒绝；
- 归档会话只能查看，不能切换；
- 切换写数据库与写工作区在同一事务；数据库失败时工作区未动，工作区失败时回滚
  数据库；逐文件写入中途失败可能留下部分文件，重试时通常表现为冲突；
- 会话更新沿用现有乐观锁，两个客户端同时切换时后到者收到并发修改错误；
- 一个会话对应一个工作区；不处理同一目录被多个会话同时写入的情况（与回退一致）；
- 目标叶子属于其他会话或不存在时，`loadBranch` 为空，返回 `INVALID_BRANCH`；
- 目标就是当前分支（`fromEntryId` 在当前分支上）时返回 `INVALID_BRANCH`；
- 两条分支树相同（没有文件差异）时仍然允许切换，只移动对话；
- 切换不会重新执行工具、不会重放旧分支的提示词；
- 旧分支的 entry 不会被删除，也不会被“恢复成当前分支”；它们只是再次成为可达历史。

## 13. 里程碑拆分

| 步骤 | 内容 |
|---|---|
| M25-1 | `BranchTrees` 树证据、回退简报写入 `fromTreeSha`/`toTreeSha`、切换保活 ref、单元测试 |
| M25-2 | `BranchResumeResult`、`BranchResumeBriefBuilder`、`SessionBranchResumeService` 与单元测试 |
| M25-3 | `POST /resume` 控制器、请求/响应 DTO、错误映射、OpenAPI 与 MockMvc 测试 |
| M25-4 | 时间线 `RESUME` 节点、分支证据改名、对比接受两种简报与参数别名 |
| M25-5 | Web：分支卡片按钮、切换预览、对比入口、切换简报渲染与样式 |
| M25-6 | CLI：`/resume`、`/timeline`、`/compare`、帮助与补全 |
| M25-7 | 集成测试、浏览器/终端实测、文档与里程碑记录、本地提交 |

## 14. 测试计划

### 14.1 单元测试

- `BranchTrees`：改动树、检查点起点树、简报 `toTreeSha` 的优先级与缺失；
- `SessionBranchResumeService`：
  - 预览读取目标树与当前树，返回文件与冲突；
  - 执行后叶子移到目标叶子、简报父节点正确、details 完整；
  - 冲突时不更新会话、不追加 entry；
  - 当前树或目标树缺失时 `available=false`，reason 可读；
  - 目标在当前分支上、目标不是分支简报、会话运行中、会话已归档；
  - 旧版回退简报（无 `fromTreeSha`）走分支回退；
  - 备注超长、往返切换后再次切回；
- `BranchResumeBriefBuilder`：两侧计数、目标意图、拒绝/失败清单、400 字截断；
- `SessionRewindService`：简报新增树字段、实时快照优先、保活调用；
- `SessionTimelineService`：`RESUME` 节点、分支字段、当前标记；
- `SessionBranchComparisonService`：接受 `resume` 简报、按 `branchEntryId` 选择、
  旧 `rewind` 简报仍可用。

### 14.2 接口与集成

- MockMvc：预览、执行、冲突、不可用、409、404；
- OpenAPI 断言：`/resume` 路径与请求/响应 schema；
- 集成测试（临时 Git 仓库 + Fake 模型）：
  1. 第一轮改文件，第二轮改另一文件；
  2. 回退到第一轮之前；
  3. 在旧分支上继续一轮；
  4. 切回第一条分支，断言工作区回到第一条分支的树、上下文包含切换简报；
  5. 再切回去，断言简报链与文件还原正确。

### 14.3 手工验收

- Web 桌面 1440×960 与移动端 390×844：
  - 时间线分支节点渲染、对比、切换预览、冲突提示、切换成功后的重载；
  - 会话中出现切换简报，历史按钮可用；控制台无错误；
- CLI 行模式与 TUI：`/timeline`、`/compare`、`/resume`，非交互拒绝；
- 冲突场景：切换后手改文件，再次切换被整体拒绝。

## 15. 风险与已知限制

| 风险 | 缓解 |
|---|---|
| 切换覆盖或删除工作区文件 | 只触碰两侧树之间的差异路径；先预览、再确认；冲突时整体放弃 |
| 未跟踪文件也在快照里，切换会删除目标分支没有的文件 | 预览列出全部受影响路径；文案用“改动”而非“还原”；与 M22 行为一致 |
| 手改发生在最后一次记录之后 | 冲突检测以记录树为基准；`fromTreeSha` 记录实时快照，保证切回时能带回手改 |
| 旧版简报没有工作区树 | 沿目标分支回退找树；仍缺失时 `available=false` 并给出可操作提示 |
| 树对象被 `git gc` 清理 | M25 起切换时保活两侧树；旧数据可能已不可用，失败时提示重新建立检查点 |
| 逐文件还原中途失败 | 事务回滚数据库，工作区可能残留部分文件；重试通常以冲突形式暴露 |
| 反复切换产生大量简报 | 简报很短，时间线仍线性；不做清理，配额另立里程碑 |
| 与并发客户端冲突 | 空闲校验 + 乐观锁，后到者收到并发修改错误 |
| 用户误以为切换会合并两条分支 | UI 文案明确“切换分支，不合并改动”；对比与预览中展示文件差异 |

## 16. 验收

- 回退后可以切回被放弃的分支，工作区与对话上下文都回到该分支；
- 切回后可以再次切到另一条分支，简报互相指向，形成对称的可逆路径；
- 预览列出的文件与执行后实际改动的文件一致；
- 手改导致冲突时，不移动叶子、不追加 entry、不写工作区；
- 切换后的第一轮对话上下文包含切换简报与目标分支最后意图；
- 时间线出现 `RESUME` 节点，对比接受两种简报，旧回退简报仍可对比与切回；
- Web 桌面与移动端走查通过，控制台无错误；
- CLI `/timeline`、`/compare`、`/resume` 实测通过，非交互拒绝切换；
- `mvn -q verify` 全绿，Checkstyle/ArchUnit 与覆盖率门槛通过；
- 文档、里程碑记录与本地提交完成。

## 17. 文档

- 本设计；
- `docs/milestones/M25.md`：实现记录与验收结果；
- `docs/05-api-and-cli.md`：`/resume` 与字段调整；
- `docs/11-interface-design.md`：分支卡片、切换预览、对比入口；
- `docs/12-git.md`：树证据与保活 ref 的命名；
- `docs/08-roadmap.md`：把 M25 从候选移到已完成；
- 根 README 的核心能力表在实现后补一条“分支切换”。

## 18. 参考

- [M22 设计：决策时间线与任意检查点回退](2026-11-05-decision-timeline-design.md)
- [M23 设计：分叉对比](2026-11-06-branch-comparison-design.md)
- [12 Git 检查点与撤销](../12-git.md)
- [05 API 与 CLI](../05-api-and-cli.md)
- [11 界面设计](../11-interface-design.md)
