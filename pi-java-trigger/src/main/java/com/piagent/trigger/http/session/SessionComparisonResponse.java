package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionBranchComparison;
import com.piagent.domain.session.model.EntryId;
import java.util.List;

/**
 * 分叉对比响应。
 *
 * @param available 是否存在可对比的分支简报
 * @param branchEntryId 本次使用的分支简报
 * @param forkEntryId 最后一个共同 entry；从根分叉时为 null
 * @param at 分支切换时间
 * @param abandoned 另一条分支
 * @param current 当前分支
 * @param files 文件差异
 * @param summary 确定性摘要
 */
public record SessionComparisonResponse(boolean available, String branchEntryId,
        String forkEntryId, String at, BranchSideView abandoned, BranchSideView current,
        FileDifferenceView files, String summary) {

    /**
     * 单侧分支投影。
     *
     * @param leafEntryId 该侧叶子
     * @param intents 用户意图
     * @param runs run 数
     * @param completedRuns 有摘要的 run 数
     * @param turns 轮数
     * @param toolCalls 工具调用数
     * @param stopReasons 停止原因
     * @param approvals 审批
     * @param verifications 验证
     * @param plan 计划
     * @param changes 改动
     * @param files 文件
     */
    public record BranchSideView(String leafEntryId, List<String> intents, int runs,
            int completedRuns, int turns, int toolCalls, List<String> stopReasons,
            ApprovalStatsView approvals, VerificationStatsView verifications,
            PlanStatsView plan, ChangeStatsView changes, List<FileSummaryView> files) {
    }

    /**
     * 审批统计。
     *
     * @param total 总数
     * @param denied 拒绝数
     * @param deniedTools 被拒工具名
     */
    public record ApprovalStatsView(int total, int denied, List<String> deniedTools) {
    }

    /**
     * 验证统计。
     *
     * @param total 总数
     * @param failed 失败数
     * @param failedCommands 失败命令
     */
    public record VerificationStatsView(int total, int failed, List<String> failedCommands) {
    }

    /**
     * 计划统计。
     *
     * @param total 总数
     * @param done 完成数
     * @param unfinished 未完成条目
     */
    public record PlanStatsView(int total, int done, List<String> unfinished) {
    }

    /**
     * 改动统计。
     *
     * @param turns 有改动的轮数
     * @param files 文件数
     * @param additions 新增行
     * @param deletions 删除行
     */
    public record ChangeStatsView(int turns, int files, int additions, int deletions) {
    }

    /**
     * 单个文件累计改动。
     *
     * @param path 路径
     * @param additions 新增行
     * @param deletions 删除行
     * @param changes 出现次数
     */
    public record FileSummaryView(String path, int additions, int deletions, int changes) {
    }

    /**
     * 文件差异。
     *
     * @param onlyAbandoned 仅另一条分支
     * @param onlyCurrent 仅当前分支
     * @param both 两边都有
     * @param truncated 是否截断
     */
    public record FileDifferenceView(List<String> onlyAbandoned, List<String> onlyCurrent,
            List<String> both, boolean truncated) {
    }

    /**
     * 从应用层投影生成响应。
     *
     * @param comparison 对比投影
     * @return 响应
     */
    public static SessionComparisonResponse from(SessionBranchComparison comparison) {
        return new SessionComparisonResponse(comparison.available(),
                id(comparison.branchEntryId()), id(comparison.forkEntryId()),
                comparison.at() == null ? null : comparison.at().toString(),
                side(comparison.abandoned()), side(comparison.current()),
                files(comparison.files()), comparison.summary());
    }

    private static BranchSideView side(SessionBranchComparison.BranchSide side) {
        SessionBranchComparison.ApprovalStats approvals = side.approvals();
        SessionBranchComparison.VerificationStats verifications = side.verifications();
        SessionBranchComparison.PlanStats plan = side.plan();
        SessionBranchComparison.ChangeStats changes = side.changes();
        return new BranchSideView(id(side.leafEntryId()), side.intents(), side.runs(),
                side.completedRuns(), side.turns(), side.toolCalls(), side.stopReasons(),
                new ApprovalStatsView(approvals.total(), approvals.denied(),
                        approvals.deniedTools()),
                new VerificationStatsView(verifications.total(), verifications.failed(),
                        verifications.failedCommands()),
                new PlanStatsView(plan.total(), plan.done(), plan.unfinished()),
                new ChangeStatsView(changes.turns(), changes.files(), changes.additions(),
                        changes.deletions()),
                side.files().stream().map(SessionComparisonResponse::file).toList());
    }

    private static FileSummaryView file(SessionBranchComparison.FileSummary file) {
        return new FileSummaryView(file.path(), file.additions(), file.deletions(),
                file.changes());
    }

    private static FileDifferenceView files(SessionBranchComparison.FileDifference files) {
        return new FileDifferenceView(files.onlyAbandoned(), files.onlyCurrent(), files.both(),
                files.truncated());
    }

    private static String id(EntryId entryId) {
        return entryId == null ? null : entryId.toString();
    }
}