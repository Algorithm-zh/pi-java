# 12 Git 检查点与撤销

> 状态：M22 已实现（2026-11-05）。Git 是**可选增强**：工作区不是仓库、机器没装
> git 或显式关闭时，Agent Loop 照常运行，只是没有改动面板与撤销能力。M22 起每次
> 运行前的检查点会被内部 ref 固定，并支持回到任意一次运行之前；M25 起分支叶子也会
> 被固定，可以在同一会话的两条已知分支之间来回切换。

## 1. 目标与边界

M13 解决三个日常问题：

1. **改了什么**：一轮工具执行后，直接看到文件列表、行数与统一 diff；
2. **改错了怎么办**：一键撤销上一轮，文件与对话一起退回发送消息之前；
3. **提交怎么写**：按改动文件给出 Conventional Commits 风格的单行建议。

明确不做：

- 不创建分支、不移动 HEAD、不写用户索引、不自动提交；
- 不替代 Git：历史、合并、推送仍由用户自己完成；
- 不接管 `.gitignore`：被忽略的文件不进入快照，也不会被撤销。

设计动机与备选方案见 [ADR 0005](adr/0005-git-checkpoints.md)。

## 2. 配置

```yaml
pi:
  git:
    enabled: true          # 缺省 true；关闭后使用空实现
    timeout-seconds: 10    # 单条 git 命令超时
    max-diff-chars: 60000  # 单个 turn diff 保留上限，超出截断
    pin-checkpoints: true  # M22：用内部 ref 固定检查点，避免被 gc 回收
```

- `enabled=true` 但工作区不是仓库时，`snapshot()` 返回空，服务降级为“没有 Git 信息”；
- `timeout-seconds` 必须大于 0，`max-diff-chars` 不能小于 1000，启动时校验；
- 关闭后 `WorkspaceGit` 由 `DisabledWorkspaceGit` 实现，查改动返回
  `available=false`，撤销返回 `GIT_UNAVAILABLE`。
- `pin-checkpoints=true` 时，每次运行开始后用
  `refs/pi/checkpoints/<sessionId>/<runId>` 指向包裹检查点树对象的内部提交，
  保证后续可回溯；固定失败只记 WARNING，不影响运行。

## 3. 快照模型

快照不碰用户索引，使用**临时索引文件**（`GIT_INDEX_FILE` 指向临时目录）：

```text
1. git rev-parse --show-toplevel          确认仓库根，失败即“不是仓库”
2. 新建临时索引
3. HEAD 存在 → read-tree <HEAD>；空仓库 → read-tree --empty
4. git -c core.autocrlf=false add -A --ignore-errors -- .
5. git write-tree                         得到工作区树对象
```

要点：

- **包含未跟踪文件**：`add -A` 把新文件也写进临时索引，因此新建文件能被看见、
  也能被撤销；`.gitignore` 仍然生效；
- **绕过换行转换**：写入与还原都带 `core.autocrlf=false`，保证对象内容与工作区
  字节一致，避免 Windows 上撤销后文件“看起来没变但 diff 不同”；
- **不产生提交**：树对象只进对象库，不移动任何 ref；`headSha` 与 `branch` 仅用于
  展示，空仓库或游离 HEAD 时为空；
- **部分文件失败可忽略**：`add --ignore-errors` 允许被占用或权限不足的单个文件
  失败，其余文件仍可快照。

## 4. 逐轮 diff

run 开始时写一条 `GIT_CHECKPOINT` entry（`runId`、`baseLeafId`、`baseTreeSha`、
`headSha`、`branch`），之后每轮工具执行结束再快照一次：

- 本轮起点树 = 上一轮终点树，第一轮起点是检查点，避免每轮重复遍历整个仓库；
- 树没有变化时不写 entry，历史里不会出现大量空记录；
- 有变化时写 `GIT_CHANGES` entry：文件列表（含二进制标记）、统一 diff（`-U3`）、
  截断标记与提交建议；
- 两类 Git entry 的 `tokenEstimate` 固定为 0，投影器跳过它们，不占用模型上下文；
- diff 使用 `--no-renames`，重命名表现为“删除 + 新增”，统计与撤销都按路径处理。

提交建议是纯启发式，不调用 LLM：全部是文档/测试/CI 时分别给 `docs`/`test`/`ci`，
涉及构建文件给 `build`，其余一律 `chore`。它只帮用户少打一行字，不声称 `feat`
或 `fix`。

## 5. 撤销语义

撤销的是**最近一次 run**（不是任意单轮）：

```text
1. 只允许空闲会话；运行中返回 INVALID_STATE_TRANSITION
2. 沿当前分支找到最近的 GIT_CHECKPOINT；没有则 GIT_UNAVAILABLE
3. 目标树 = 该 run 最后一轮 GIT_CHANGES 的 treeSha；没有改动时等于检查点树
4. 快照当前工作区，逐个比对“本轮改动过的路径”：
   当前 blob ≠ 本轮结束时 blob → 记为冲突
5. 有冲突：整体放弃，不写任何文件，返回 rewound=false + 冲突列表
6. 无冲突：
   - 本轮新增的文件 → 删除；
   - 本轮修改/删除的文件 → 用临时索引 checkout-index 从起点树写回；
   - 会话分支退回到检查点的 baseLeafId；
   - baseLeafId 为 null（撤销第一轮）→ 同时清空该会话全部 entry。
```

为什么第一轮要清空日志：根 entry 必须是 `seq=1` 且 `parentId=null`。分支退回空会话
后，下一次追加需要成为新的根；若旧 entry 还在，`seq` 会从 2 开始且没有父节点，
违反领域不变量。工作区还原与日志清理在同一事务内完成。

撤销的硬约束：

- **不覆盖用户新改动**：只要本轮碰过的文件在本轮之后又被改过，整个撤销拒绝执行，
  避免对话与文件一半新一半旧；
- **只碰本轮路径**：用户后来新增的无关文件保持不变；
- **不改分支/HEAD**：只更新会话在数据库里的叶子指针，仓库状态与撤销前一致；
- **路径安全**：删除文件前做 `normalize` 并校验仍在仓库根之内。

## 6. HTTP API

### 6.1 查询最近的改动

```http
GET /api/v1/sessions/{sessionId}/changes
```

```json
{
  "available": true,
  "canUndo": true,
  "branch": "main",
  "headSha": "0f3c...",
  "fileCount": 1,
  "additions": 1,
  "deletions": 0,
  "suggestedCommit": "docs: 更新 notes.md",
  "turns": [
    {
      "turn": 1,
      "files": [
        {"path": "notes.md", "kind": "ADDED", "additions": 1, "deletions": 0, "binary": false}
      ],
      "diff": "diff --git a/notes.md b/notes.md\n...",
      "truncated": false,
      "suggestedCommit": "docs: 更新 notes.md"
    }
  ]
}
```

- 不访问磁盘，只读会话分支上的 Git entry，因此重启后仍可查询；
- 非 Git 工作区返回 `available=false`、`canUndo=false`、`turns=[]`，HTTP 仍为 200；
- `canUndo` 由“存在检查点 + 会话空闲”共同决定；`fileCount` 按路径跨轮去重，
  行数按轮累加。

### 6.2 撤销上一轮

```http
POST /api/v1/sessions/{sessionId}/undo
```

成功：

```json
{"rewound": true, "restored": ["notes.md"], "conflicts": []}
```

冲突（HTTP 仍为 200）：

```json
{
  "rewound": false,
  "restored": [],
  "conflicts": [
    {"path": "notes.md", "reason": "本轮之后工作区又发生了变化，为避免覆盖已跳过"}
  ]
}
```

错误：

| 场景 | code | HTTP |
|---|---|---|
| 会话不存在 | `SESSION_NOT_FOUND` | 404 |
| 会话运行中 / 已归档 | `INVALID_STATE_TRANSITION` / `SESSION_ARCHIVED` | 409 |
| 没有检查点或不是仓库 | `GIT_UNAVAILABLE` | 409 |
| git 执行失败 | `GIT_OPERATION_FAILED` | 500 |

冲突不当作 HTTP 错误，前端需要拿到具体路径自己解释，而不是只显示“撤销失败”。

### 6.3 决策时间线与任意检查点回溯（M22）

```http
GET  /api/v1/sessions/{id}/timeline
POST /api/v1/sessions/{id}/rewind
```

时间线按运行分组，节点顺序与 `seq` 一致。每个节点包含：

- `type`：`RUN`、`REWIND` 或 `RESUME`；
- `checkpointEntryId`：该次运行开始前的检查点 entry；
- `branchEntryId`：分支节点（回溯或切回简报）对应的 entry，供 Web 与 CLI 打开
  详情或对比；
- 摘要：用户意图、计划进度、审批结论、验证结果、改动文件数；
- `evidence`：本次运行的待办、审批、验证与逐轮改动，供 Web 展开证据。分支节点的
  `leftEntries` / `leftRuns` 表示那条被离开的分支尾段规模（旧数据仍读
  `discardedEntries` / `discardedRuns`）。

回溯请求体：

```json
{"checkpointEntryId": "01JD...", "dryRun": true, "note": "换一种修法"}
```

`checkpointEntryId` 是时间线节点的 `checkpointEntryId`，不是运行 ID；`note`
可选，最多 200 字。`dryRun` 缺省为 true，只做预览，不写任何东西。响应：

```json
{
  "dryRun": false,
  "rewound": true,
  "restorable": true,
  "files": ["notes.md", "src/App.java"],
  "restored": ["notes.md", "src/App.java"],
  "conflicts": [],
  "briefEntryId": "01JE...",
  "discardedEntries": 14,
  "discardedRuns": 1,
  "brief": "从 10:05 的决策点回退：丢弃 1 轮对话、0 次被拒绝的审批、0 次失败的验证。已还原 2 个文件（notes.md、src/App.java）。"
}
```

语义：

- 只有空闲会话可回溯；归档会话返回 409 `SESSION_ARCHIVED`，运行中返回
  409 `INVALID_STATE_TRANSITION`；
- `checkpointEntryId` 不在当前分支上返回 409 `INVALID_BRANCH`；
- 先做冲突预检：只要有文件在目标检查点之后又被修改，整体放弃，返回 HTTP 200、
  `rewound=false`，`conflicts` 带路径与原因，工作区与对话都不变；确认后工作区
  再次变化时返回 500 `GIT_OPERATION_FAILED`，同样不产生半完成状态；
- 成功时先在事务内移动叶子并追加简报，再写工作区：数据库步骤失败会回滚事务，
  此时工作区尚未改动；工作区写入失败则抛异常回滚事务、叶子与简报。`baseLeafId`
  为空表示回到第一次运行之前，叶子置空，旧 entry 全部保留在日志中，只是不再属于
  当前分支；否则把叶子节点移回 `baseLeafId`；
- 移动后追加一条 `BRANCH_SUMMARY`（`details.kind = "rewind"`）作为“回溯简报”，
  下一次请求会把它作为上下文的一部分；`fromEntryId` 记录回退前的旧叶子，简报
  entry 的 `parentId` 是回退后重新接上的节点，Web 用这两个边界只取
  `GET /sessions/{id}/entries?leafId={fromEntryId}` 中回退点之后被放弃的片段，
  不重复聊天区已有的对话；片段里若包含更早的回溯简报，可以继续点开更早的片段。
  不会写回当前上下文。回到第一次运行之前时，简报是旧日志之后的新根
  （`seq > 1`、无父节点）；
- 响应里的 `restored` 是实际写入的文件，`files` 是计划还原的文件；简报的
  `details.restoredPaths` 最多保留 50 个路径，摘要里展示前 3 个；`discarded`
  统计被移出当前分支的 entry 与运行次数；
- 简报的 `details.fromTreeSha` / `toTreeSha` 记录还原前后的工作区树，切回时优先
  使用它；旧简报没有这两个字段时回退到树证据；
- 不移动 HEAD、不修改分支、不自动提交。

### 6.4 分支切回（M25）

```http
POST /api/v1/sessions/{id}/resume
```

请求体：

```json
{"branchEntryId": "01JE...", "dryRun": true, "note": "先跑测试"}
```

`branchEntryId` 是时间线 `REWIND` / `RESUME` 节点的 `branchEntryId`，必须位于当前
分支上；`note` 可选，最多 200 字。语义：

- 切回目标分支的叶子：分支简报的 `fromEntryId` 是离开时的旧叶子，切回后对话从
  那里继续；目标工作区优先取简报的 `fromTreeSha`，缺失时按树证据回溯该分支最后
  一份 `GIT_CHANGES.treeSha`、检查点 `baseTreeSha` 或简报 `toTreeSha`；
- 当前工作区树取当前分支最后一份树证据；两棵树都可用时才计算还原计划，任一缺失
  返回 `available=false` 与原因，不写任何数据；
- 确认前先预览：`files` 是计划还原的文件，有冲突时 `restorable=false`，确认按钮
  不可用；预览不写文件；
- 执行顺序与回溯一致：写实时树快照 → 事务内移动叶子到目标叶子 → 追加
  `BRANCH_SUMMARY`（`details.kind = "resume"`）→ 还原工作区 → 固定两侧树；
  确认后工作区再变化则抛 500 `GIT_OPERATION_FAILED`，不产生半完成切换；
- 旧叶子与目标叶子都会用 `refs/pi/checkpoints/<sessionId>/branch-<leaf>` 固定，
  即使它们不是运行检查点也能再次切回或对比；
- 成功响应返回 `switched=true`、`targetLeafId`、`briefEntryId`、两侧 entry/run 数
  与简报；切回简报会进入下一轮上下文，用户可以在两条已走过的路之间来回切换；
- 不移动 HEAD、不修改分支、不自动提交。

### 6.5 检查点固定

`pin-checkpoints=true` 时，运行开始后用 `git commit-tree` 生成内部提交，
`git update-ref` 写入 `refs/pi/checkpoints/<sessionId>/<runId>`：

- 提交只用于让树对象可达，`commit-tree` 的父为空，不进入任何分支；
- 写入时显式提供 `GIT_AUTHOR_NAME`/`GIT_AUTHOR_EMAIL` 等身份变量，避免依赖
  用户 git 配置；同一树对象重复固定只会生成新的内部提交，ref 始终指向最新一次；
- 回溯与切回额外用 `refs/pi/checkpoints/<sessionId>/branch-<leaf>` 固定两侧分支
  叶子对应的树，保证没有运行检查点的叶子也能再次切回或对比；
- 删除会话时同时删除该会话下的所有 `refs/pi/checkpoints/<sessionId>/`；
- 固定是尽力而为：失败只记 WARNING，不阻断运行，也不影响后续回溯（只是检查点
  可能在 `git gc --prune=now` 后失效）。

## 7. CLI

交互式 `pi chat` 与逐行模式都支持：

```text
/diff    最近一次运行的分支、文件、行数与提交建议；正文最多 120 行，
         其余提示到 Web 面板查看
/undo    撤销上一轮：还原文件并回退对话；冲突时打印每个文件的原因
```

运行中出现 `WORKSPACE_CHANGED` 时，TUI 额外打印一行：

```text
  ± 改动 2 个文件 +18 -4
```

`CliProjection` 也把 `GIT_CHECKPOINT` / `GIT_CHANGES` 渲染成简短摘要，`/tree`
能看到它们，但不会把 diff 塞进历史列表。

## 8. Web

对话区底部出现“本轮改动”面板：

- 摘要行：分支、去重文件数、`+新增 −删除`、撤销按钮；
- 展开后：逐轮文件列表、提交建议、按行着色的统一 diff；
- 撤销前用原生确认框说明“文件还原 + 对话退回”，避免误触；
- 冲突时展开面板并列出冲突文件与原因，撤销按钮不掩盖错误；
- 移动端面板收缩为单列，隐藏次要统计，diff 横向滚动；
- 面板只在 `available && turns.length > 0` 时显示；切换/删除会话自动清空。

所有路径与 diff 文本都用 `textContent` 写入，禁止 `innerHTML`。

## 9. 失败与降级

| 场景 | 行为 |
|---|---|
| 工作区不是仓库 / 没装 git | 快照为空，run 正常；API `available=false`，CLI 提示不可用 |
| 单条 git 命令超时或失败 | 快照/改动返回空；撤销返回 `GIT_OPERATION_FAILED` |
| 写 Git entry 失败 | 只记 WARNING，不影响 run 与对话 |
| 快照后文件被外部进程占用 | `add --ignore-errors` 跳过该文件；撤销时可能因冲突拒绝 |
| 用户在本轮后改过文件 | 撤销整体拒绝，工作区一个字节都不写 |
| 会话运行中撤销 | 409，必须先停止或等待结束 |

Git 永远不能让 Agent Loop 失败：所有记录路径都包在 try/catch 中，失败只损失
“改动可见性”，不损失对话本身。

## 10. 已知限制

- 未被内部 ref 固定（`pin-checkpoints=false` 或固定失败）的树对象只存在于对象库；
  `git gc --prune=now` 之后旧检查点将无法还原。日常 gc 有宽限期，一般不影响使用；
- 回溯以运行开始前的检查点为单位，不能回到单轮中间状态，也不能只撤销某一轮工具
  调用；
- 回溯会丢弃目标检查点之后的对话与文件改动；对话记录保留在日志中，可通过简报的
  “查看被放弃的记录”只读回看回退点之后的片段，多次回退可以逐层往前追溯，但不能
  直接恢复到当前分支；文件改动无法自动找回；
- 本修复之前执行的首次回退（回到第一轮之前）已经物理删除旧 entry，无法再找回；
- 内部检查点 ref 只在该会话删除时清理，长期保留会让对象库持续增长。
- 新增文件被删除后可能留下空目录（Git 不跟踪目录）；
- 不还原子模块内部内容；子模块只作为 gitlink 参与快照；
- 二进制文件能在列表中出现，但 diff 不展示内容；
- 重命名按删除 + 新增处理，不保留重命名语义；
- 提交建议只是启发式，不判断改动是否正确，也不会自动提交；
- 工作区很大时，每轮快照的磁盘扫描成本随文件数增长；`max-diff-chars` 只限制
  文本大小，不限制扫描范围。

## 11. 相关文档

- [ADR 0005：临时索引检查点](adr/0005-git-checkpoints.md)
- [M13 里程碑记录](milestones/M13.md)
- [M22 里程碑记录](milestones/M22.md)：决策时间线与任意检查点回溯
- [04 运行时与事件](04-runtime-and-events.md)：`workspace.changed` 事件
- [05 API 与 CLI](05-api-and-cli.md)：接口与命令契约
- [11 界面设计](11-interface-design.md)：改动面板的交互细节
