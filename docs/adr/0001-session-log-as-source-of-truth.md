# ADR 0001：会话日志是唯一事实源

- 状态：Accepted
- 日期：2026-10-21
- 决策者：pi-java 架构

## 背景

Agent 会话同时包含用户消息、模型输出、工具调用与结果、压缩摘要、审批决策和运行摘要。
常见实现会维护两套状态：

1. 一张可变的 `messages` 表，更新“当前对话”；
2. 一份运行态数组，完成后再序列化保存。

这套做法在分支、压缩、崩溃恢复和审计上会迅速失控：

- 分支需要复制或重写消息，容易产生分叉不一致；
- 压缩要么删除历史（不可追溯），要么另建摘要表（两套事实）；
- 审批拒绝只存在内存或日志文本里，无法可靠回放；
- 进程崩溃后，运行态数组丢失，数据库只剩“半截对话”。

pi 的 JSONL 树形会话提供了一个更简单的模型：**只追加 entry，用 parentId 组成树，
leaf 指向当前位置；上下文由 leaf 回溯投影得到。**

## 决策

pi-java 采用 **append-only 会话 entry 日志作为唯一事实源**：

- 表 `pi_session_entry` 只插入，不更新、不删除；
- 每条 entry 携带 `id / session_id / seq / parent_id / entry_type / payload_version / payload`；
- 分支只修改 `pi_session.leaf_entry_id`，不复制历史；
- 用户消息、助手消息、工具结果、压缩摘要、分支摘要、模型切换、审批决策、运行摘要
  全部是 entry；
- 当前上下文不落库，由 `SessionContextProjector` 从 leaf 回溯并按最新 `COMPACTION`
  规则投影得到；
- `COMPACTION` entry 保存 `firstKeptEntryId`，既不删除旧 entry，也不让旧内容进入上下文；
- 审批表 `pi_approval_request` 只是待办投影，审计以 `APPROVAL` entry 为准；
- Run 只存在于进程内，结束时写 `RUN_SUMMARY` entry。

## 结果

正面：

- 分支、压缩、审批、恢复共享同一套日志语义，减少状态源；
- 任意历史节点可回放、可审计，压缩不丢数据；
- 崩溃恢复只需补齐悬空 toolCall 与缺失的 run summary；
- 领域投影是纯函数，可用黄金样本离线测试。

负面：

- entry 会持续增长，需要归档/备份策略；
- 每次上下文组装都要读分支 entry 并投影，读放大明显；
- 读取端必须兼容旧 `payload_version` 与未知字段；
- 不能直接对“当前消息”做 UPDATE，某些查询需要额外投影或索引。

## 替代方案

### 方案 A：可变 `messages` 表 + 摘要表

简单、查询快，但分支要复制、压缩会丢历史、审批与运行摘要无处安放。
与“可回溯、可审计”的目标冲突，放弃。

### 方案 B：完整 Event Sourcing + 独立事件存储

语义最纯，但引入事件版本、快照、重放器等大量基础设施；
v0.1 单机场景不需要，复杂度收益比不划算。

### 方案 C：直接照搬 pi 的 JSONL 文件

实现最轻，但缺少数据库约束、并发事务、按会话/类型查询与多进程恢复能力；
pi-java 需要 MySQL 落地，因此采用“关系表 + JSON payload”的折中。

## 强制不变量

- entry 追加后不可修改；
- `(session_id, seq)` 唯一；
- `parent_id` 必须属于同一会话；
- 压缩切点不得落在 toolResult；
- `leaf_entry_id` 必须指向本会话的合法节点；
- 每个 run 结束必须留下 `RUN_SUMMARY`；
- 任何“删除会话”都通过级联删除整棵日志，不支持删除单条 entry。
