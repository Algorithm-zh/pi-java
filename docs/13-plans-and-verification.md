# 13 计划、待办与自动验证

> 状态：M14 已实现（2026-10-26）。计划条目进入会话日志，`todo` 工具由模型维护；
> 自动验证在 run 改过工作区且模型准备收尾时执行一次，失败输出回写后最多重试一次。
> 配置项：`pi.verify.*`；无需数据库迁移。

## 1. 目标

让代理的任务推进从“只有一段最终文本”变成可观察、可验证的过程：

- 用户能看见代理打算做几步、当前做到哪一步；
- 计划是会话日志的一部分，重启、分支切换、撤销后仍然一致；
- 代理改完文件后自动跑一次项目验证命令，而不是口头声称“已完成”；
- 验证失败时把真实输出交回模型修复一次，避免无限循环；
- Web 与 CLI 使用同一份数据，不新增第二事实源。

## 2. 计划与待办

### 2.1 领域模型

- `Plan`：不可变的完整待办快照，最多 20 条；
- `PlanItem(content, status)`：`content` 非空、去首尾空白、最多 200 字；
- `PlanItemStatus`：`PENDING` / `IN_PROGRESS` / `DONE`，线名分别为
  `pending` / `in_progress` / `done`；
- 不变量：
  - 最多一条 `in_progress`；
  - `currentIndex()` 优先返回 `in_progress`，否则返回第一条 `pending`；
  - `completedCount()` 只统计 `DONE`；
  - 空列表表示清空计划。

### 2.2 日志表示

每次更新写一条完整的 `PLAN` entry（`PlanPayload(runId, items)`），
不追加增量事件。分支上最新的一条 `PLAN` 就是当前计划；切分支、撤销、
重启都只需重新投影，不需要额外表或迁移。

`PLAN` 属于元数据 entry，不进入模型上下文。

### 2.3 `todo` 工具

| 项目 | 值 |
|---|---|
| 名称 | `todo` |
| 参数 | `todos: [{content, status}]`，整表替换 |
| 风险 | `LOW` |
| 执行 | `SEQUENTIAL` |
| 审批 | 不需要 |

行为：

- 传入空数组 → 清空计划，返回“已清空待办计划”；
- 未知状态、缺少 `content`、超过 20 条、多于一条 `in_progress` → 参数错误；
- 成功时返回文本摘要（`1/3 完成`、当前项、`[x]/[>]/[ ]` 列表），
  `details` 带 `total`、`completed` 与条目列表，供 UI 与模型共同使用；
- 每次成功更新发布 `plan.updated` 事件并写 `PLAN` entry。

### 2.4 查询 API

```http
GET /api/v1/sessions/{id}/plan
```

```json
{
  "hasPlan": true,
  "total": 3,
  "completed": 1,
  "items": [
    {"content": "读代码", "status": "done"},
    {"content": "改实现", "status": "in_progress"},
    {"content": "跑测试", "status": "pending"}
  ],
  "verification": {
    "command": "mvn -q test",
    "passed": false,
    "exitCode": 1,
    "timedOut": false,
    "truncated": false,
    "durationMs": 48213,
    "output": "Tests run: 3, Failures: 1 ..."
  }
}
```

- `verification` 是当前分支最近一次自动验证；从未验证时为 `null`；
- HTTP 面只读：计划只能由模型通过 `todo` 工具维护，避免两份互相覆盖的来源。

## 3. 自动验证

### 3.1 配置

```yaml
pi:
  verify:
    command: "${PI_VERIFY_COMMAND:}"   # 留空表示关闭
    timeout-seconds: 120
    max-output-chars: 6000
    retry-on-failure: true
```

- 命令由用户配置，不来自模型，因此不进入审批；
- 通过系统 shell 执行（Windows `cmd.exe /c`，其他 `/bin/sh -c`），
  工作目录是会话的 `cwd`；
- 合并 stdout/stderr，超时后终止整棵进程树，输出按上限截断但继续排空管道。

### 3.2 触发条件

同时满足才执行：

1. 当前 run 至少执行过一次 `write` / `edit` / `bash`；
2. 模型准备以 `STOP` / `LENGTH` 收尾（不是取消或错误）；
3. 配置了验证命令；
4. 本 run 尚未验证过（每个 run 最多一次）。

未改文件、只读任务、空命令都不会执行验证。

### 3.3 结果与回写

- 无论通过与否，都写一条 `VERIFICATION` entry（`VerificationPayload`）；
- 通过 → 发布 `verification.completed`，run 正常结束；
- 失败且 `retry-on-failure=true` → 先写 entry，再追加一条用户消息，
  内容包括命令、退出码/超时、截断后的输出与“请先修复失败原因”；
  循环继续一轮，让模型在同一 run 内修复；
- 每个 run 最多回写一次；第二次失败只记录结果，不再回写；
- `retry-on-failure=false` 时只记录结果，不追加消息；
- 验证执行或回写抛异常只记日志，run 仍正常结束。

### 3.4 展示

- SSE：`plan.updated`、`verification.completed`；
- Web：输入框上方的待办面板（进度、当前项、展开列表、验证状态与输出），
  历史消息流中的“待办更新”和“自动验证”折叠卡片；
- CLI：`pi run` 输出 `[待办]` 与 `[验证]` 摘要行；
  TUI 输出 `□ 待办` 与 `√/× 验证` 短行；`/plan` 打印当前计划；
- 重连或刷新后通过 `GET /sessions/{id}/plan` 恢复面板。

## 4. 事件字段

| type | data 关键字段 |
|---|---|
| `plan.updated` | `runId, total, completed, items[{content, status}]` |
| `verification.completed` | `runId, command, passed, exitCode?, timedOut, truncated, durationMs, output（截断）` |

## 5. 已知限制

- 计划是整表替换，没有“追加一条”或“只改状态”的增量接口；
- 每个 run 只验证一次、只重试一次，复杂项目需要多次验证时应由模型显式调用
  `bash` 或拆分任务；
- 验证命令是单条 shell 命令，不支持矩阵或多项目并行；
- 验证失败回写的是用户消息，会进入模型上下文并计入 token；
- 计划不做持久化投影缓存，读取时从分支末端重新投影（当前量级可接受）。
