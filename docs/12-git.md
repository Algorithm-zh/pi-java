# 12 Git 检查点与撤销

> 状态：M13 已实现（2026-10-25）。Git 是**可选增强**：工作区不是仓库、机器没装
> git 或显式关闭时，Agent Loop 照常运行，只是没有改动面板与撤销能力。

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
```

- `enabled=true` 但工作区不是仓库时，`snapshot()` 返回空，服务降级为“没有 Git 信息”；
- `timeout-seconds` 必须大于 0，`max-diff-chars` 不能小于 1000，启动时校验；
- 关闭后 `WorkspaceGit` 由 `DisabledWorkspaceGit` 实现，查改动返回
  `available=false`，撤销返回 `GIT_UNAVAILABLE`。

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

- 树对象只存在于对象库，没有任何 ref 指向它们；`git gc --prune=now` 之后旧检查点
  将无法还原。日常 gc 有宽限期，一般不影响使用；
- 只能撤销最近一次 run，不能回退到更早的检查点或单轮中间状态；
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
- [04 运行时与事件](04-runtime-and-events.md)：`workspace.changed` 事件
- [05 API 与 CLI](05-api-and-cli.md)：接口与命令契约
- [11 界面设计](11-interface-design.md)：改动面板的交互细节
