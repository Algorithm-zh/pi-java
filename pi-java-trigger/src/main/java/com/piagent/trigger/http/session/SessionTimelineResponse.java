package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionTimeline;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.util.List;

/**
 * 当前分支的决策时间线响应。
 *
 * @param available 当前分支是否存在 Git 检查点
 * @param currentCheckpointEntryId 当前叶子所在 RUN 节点的检查点；叶子是分支简报
 *     （REWIND 或 RESUME 节点）时为 null
 * @param nodes 按分支位置排列的节点
 */
public record SessionTimelineResponse(boolean available, String currentCheckpointEntryId,
        List<NodeResponse> nodes) {

    /**
     * 一个决策点。
     *
     * <p>REWIND / RESUME 节点只带分支简报：{@code checkpointEntryId}、{@code plan}、
     * {@code approvals}、{@code verification}、{@code changes} 与 {@code evidence}
     * 均为 null，{@code intent} 与 {@code stopReason} 为空串，统计与轮数计数为 0。
     * 反之 {@code brief} 只在分支节点有值，RUN 节点为空串。</p>
     *
     * @param type RUN、REWIND 或 RESUME
     * @param checkpointEntryId 检查点 entry；分支节点为 null
     * @param branchEntryId 分支简报 entry；RUN 节点为 null
     * @param runId run 标识；分支简报没有对应 run 时为空串
     * @param at 节点时间
     * @param current 是否包含当前叶子
     * @param rewindable 是否允许回退
     * @param intent 触发这次 run 的用户文本；分支节点为空串
     * @param plan 计划统计；分支节点为 null
     * @param approvals 审批统计；分支节点为 null
     * @param verification 验证统计；分支节点为 null
     * @param changes 改动统计；分支节点为 null
     * @param stopReason 停止原因；分支节点为空串
     * @param turns 轮数；分支节点为 0
     * @param toolCalls 工具调用数；分支节点为 0
     * @param brief 分支简报；RUN 节点为空串
     * @param leftEntries 离开侧 entry 数；RUN 节点为 0
     * @param leftRuns 离开侧 run 数；RUN 节点为 0
     * @param evidence 可展开的证据；分支节点为 null
     */
    public record NodeResponse(String type, String checkpointEntryId, String branchEntryId,
            String runId, String at,
            boolean current, boolean rewindable, String intent, PlanSummaryView plan,
            ApprovalSummaryView approvals, VerificationSummaryView verification,
            ChangeSummaryView changes, String stopReason, int turns, int toolCalls,
            String brief, int leftEntries, int leftRuns, EvidenceView evidence) {
    }

    /** 计划统计。 */
    public record PlanSummaryView(int total, int done) {
    }

    /** 审批统计。 */
    public record ApprovalSummaryView(int total, int denied) {
    }

    /** 验证统计。 */
    public record VerificationSummaryView(int total, int failed) {
    }

    /** 改动统计。 */
    public record ChangeSummaryView(int turns, int files, int additions, int deletions) {
    }

    /**
     * 可展开的证据。
     *
     * @param plan 待办快照
     * @param approvals 审批
     * @param verifications 自动验证
     * @param changes 逐轮改动
     */
    public record EvidenceView(List<PlanItemView> plan, List<ApprovalView> approvals,
            List<VerificationView> verifications, List<ChangeView> changes) {
    }

    /** 待办条目。 */
    public record PlanItemView(String content, String status) {
    }

    /** 审批记录。 */
    public record ApprovalView(String toolName, String decision, String reason) {
    }

    /** 验证记录。 */
    public record VerificationView(String command, boolean passed, Integer exitCode,
            boolean timedOut, String output) {
    }

    /** 一轮改动。 */
    public record ChangeView(int turn, String suggestedCommit, List<FileView> files,
            String diff) {
    }

    /** 改动文件。 */
    public record FileView(String path, String kind, int additions, int deletions,
            boolean binary) {
    }

    /**
     * 从应用层投影生成响应。
     *
     * @param timeline 时间线投影
     * @return 响应
     */
    public static SessionTimelineResponse from(SessionTimeline timeline) {
        return new SessionTimelineResponse(timeline.available(),
                timeline.currentCheckpointEntryId() == null ? null
                        : timeline.currentCheckpointEntryId().toString(),
                timeline.nodes().stream().map(SessionTimelineResponse::node).toList());
    }

    private static NodeResponse node(SessionTimeline.Node node) {
        SessionTimeline.RunEvidence run = node.run();
        SessionTimeline.BranchEvidence branch = node.branch();
        SessionTimeline.Stats stats = run == null ? null : run.stats();
        return new NodeResponse(node.kind().name(), id(node.checkpointEntryId()),
                branch == null ? null : id(branch.entryId()),
                node.runId(), node.at().toString(), node.current(), node.rewindable(),
                run == null ? "" : run.intent(),
                stats == null ? null : new PlanSummaryView(stats.plan().total(),
                        stats.plan().done()),
                stats == null ? null : new ApprovalSummaryView(stats.approvals().total(),
                        stats.approvals().denied()),
                stats == null ? null : new VerificationSummaryView(
                        stats.verification().total(), stats.verification().failed()),
                stats == null ? null : new ChangeSummaryView(stats.changes().turns(),
                        stats.changes().files(), stats.changes().additions(),
                        stats.changes().deletions()),
                run == null ? "" : run.stopReason(),
                run == null ? 0 : run.turns(),
                run == null ? 0 : run.toolCalls(),
                branch == null ? "" : branch.brief(),
                branch == null ? 0 : branch.leftEntries(),
                branch == null ? 0 : branch.leftRuns(),
                evidence(run));
    }

    private static EvidenceView evidence(SessionTimeline.RunEvidence run) {
        if (run == null) {
            return null;
        }
        return new EvidenceView(
                run.planItems().stream()
                        .map(item -> new PlanItemView(item.content(), item.status().wireName()))
                        .toList(),
                run.approvals().stream().map(SessionTimelineResponse::approval).toList(),
                run.verifications().stream().map(SessionTimelineResponse::verification).toList(),
                run.changes().stream().map(SessionTimelineResponse::change).toList());
    }

    private static ApprovalView approval(ApprovalPayload payload) {
        return new ApprovalView(payload.toolName(), payload.decision(),
                payload.reason() == null ? "" : payload.reason());
    }

    private static VerificationView verification(VerificationPayload payload) {
        return new VerificationView(payload.command(), payload.passed(), payload.exitCode(),
                payload.timedOut(), payload.output());
    }

    private static ChangeView change(GitChangeSetPayload payload) {
        return new ChangeView(payload.turn(), payload.suggestedCommit(),
                payload.files().stream().map(SessionTimelineResponse::file).toList(),
                payload.diff());
    }

    private static FileView file(FileChange change) {
        return new FileView(change.path(), change.kind().name(), change.additions(),
                change.deletions(), change.binary());
    }

    private static String id(EntryId entryId) {
        return entryId == null ? null : entryId.toString();
    }
}
