package com.piagent.application.timeline;

import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.time.Instant;
import java.util.List;

/**
 * 当前分支的决策时间线。
 *
 * @param available 当前分支是否至少有一个 Git 检查点
 * @param currentCheckpointEntryId 当前叶子所在 RUN 节点的检查点；叶子是回溯简报时为 null
 * @param nodes 按分支位置排列的节点
 */
public record SessionTimeline(boolean available, EntryId currentCheckpointEntryId,
        List<Node> nodes) {

    /** 节点类型。 */
    public enum NodeKind {
        RUN,
        REWIND
    }

    /**
     * 一个决策点。
     *
     * @param kind 节点类型
     * @param checkpointEntryId 检查点 entry；退化节点为 null
     * @param runId run 标识；退化节点可能为空串
     * @param at 节点时间
     * @param current 是否包含当前叶子
     * @param rewindable 是否允许回退
     * @param run RUN 节点的证据；REWIND 节点为 null
     * @param rewind 回溯简报；RUN 节点为 null
     */
    public record Node(NodeKind kind, EntryId checkpointEntryId, String runId, Instant at,
            boolean current, boolean rewindable, RunEvidence run, RewindEvidence rewind) {
    }

    /**
     * RUN 节点的证据。
     *
     * @param intent 触发这次 run 的用户文本
     * @param stats 汇总统计
     * @param stopReason 停止原因；尚未结束时为空串
     * @param turns 轮数；没有 run 摘要时为 0
     * @param toolCalls 工具调用数；没有 run 摘要时为 0
     * @param planItems 节点结束时的待办快照
     * @param approvals 节点内的审批
     * @param verifications 节点内的自动验证
     * @param changes 节点内的逐轮改动
     */
    public record RunEvidence(String intent, Stats stats, String stopReason,
            int turns, int toolCalls, List<PlanItem> planItems,
            List<ApprovalPayload> approvals, List<VerificationPayload> verifications,
            List<GitChangeSetPayload> changes) {
    }

    /**
     * REWIND 节点的简报。
     *
     * @param brief 摘要正文
     * @param discardedEntries 被丢弃的原始 entry 数
     * @param discardedRuns 被丢弃的 run 数
     */
    public record RewindEvidence(String brief, int discardedEntries, int discardedRuns) {
    }

    /**
     * RUN 节点统计。
     *
     * @param plan 计划
     * @param approvals 审批
     * @param verification 验证
     * @param changes 改动
     */
    public record Stats(PlanSummary plan, ApprovalSummary approvals,
            VerificationSummary verification, ChangeSummary changes) {
    }

    /** 计划统计。 */
    public record PlanSummary(int total, int done) {
    }

    /** 审批统计。 */
    public record ApprovalSummary(int total, int denied) {
    }

    /** 验证统计。 */
    public record VerificationSummary(int total, int failed) {
    }

    /**
     * 改动统计。
     *
     * @param turns 有改动的轮数
     * @param files 不同文件数
     * @param additions 新增行
     * @param deletions 删除行
     */
    public record ChangeSummary(int turns, int files, int additions, int deletions) {
    }
}
