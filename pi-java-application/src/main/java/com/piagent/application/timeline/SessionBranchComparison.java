package com.piagent.application.timeline;

import com.piagent.domain.session.model.EntryId;
import java.time.Instant;
import java.util.List;

/**
 * 另一条分支与当前分支的只读对比。
 *
 * <p>两侧都只保留截断后的文本与计数；所有结论由服务端生成，调用方不再自行
 * 推断分支关系。没有分支简报时 {@code available = false}，其余字段为空值。</p>
 *
 * @param available 当前分支是否存在分支简报
 * @param branchEntryId 本次对比使用的分支简报 entry
 * @param forkEntryId 两条分支最后一个共同 entry；从会话根分叉时为 null
 * @param at 分支切换发生时间
 * @param abandoned 另一条分支在分叉点之后的投影
 * @param current 当前分支在分叉点之后的投影
 * @param files 路径级文件差异
 * @param summary 确定性中文摘要，最多 500 字
 */
public record SessionBranchComparison(boolean available, EntryId branchEntryId,
        EntryId forkEntryId, Instant at, BranchSide abandoned, BranchSide current,
        FileDifference files, String summary) {

    /**
     * 生成不可用的对比。
     *
     * @return 两侧为空、available 为 false 的对比
     */
    public static SessionBranchComparison unavailable() {
        BranchSide empty = BranchSide.empty();
        return new SessionBranchComparison(false, null, null, null, empty, empty,
                FileDifference.empty(), "");
    }

    /**
     * 单侧分支在分叉点之后的投影。
     *
     * @param leafEntryId 该侧分支的叶子
     * @param intents 用户意图，按出现顺序
     * @param runs 检查点数，即 run 数
     * @param completedRuns 有 run 摘要的 run 数
     * @param turns 轮数合计
     * @param toolCalls 工具调用数合计
     * @param stopReasons 去重后的停止原因
     * @param approvals 审批统计
     * @param verifications 验证统计
     * @param plan 计划统计
     * @param changes 改动统计
     * @param files 改过的文件，按首次出现顺序
     */
    public record BranchSide(EntryId leafEntryId, List<String> intents, int runs,
            int completedRuns, int turns, int toolCalls, List<String> stopReasons,
            ApprovalStats approvals, VerificationStats verifications, PlanStats plan,
            ChangeStats changes, List<FileSummary> files) {

        /**
         * 规范化列表与空统计。
         *
         * @param leafEntryId 叶子
         * @param intents 意图
         * @param runs 运行数
         * @param completedRuns 完成数
         * @param turns 轮数
         * @param toolCalls 工具调用数
         * @param stopReasons 停止原因
         * @param approvals 审批
         * @param verifications 验证
         * @param plan 计划
         * @param changes 改动
         * @param files 文件
         */
        public BranchSide {
            intents = intents == null ? List.of() : List.copyOf(intents);
            stopReasons = stopReasons == null ? List.of() : List.copyOf(stopReasons);
            approvals = approvals == null ? ApprovalStats.empty() : approvals;
            verifications = verifications == null ? VerificationStats.empty() : verifications;
            plan = plan == null ? PlanStats.empty() : plan;
            changes = changes == null ? ChangeStats.empty() : changes;
            files = files == null ? List.of() : List.copyOf(files);
        }

        /**
         * 空投影。
         *
         * @return 所有计数为 0 的一侧
         */
        public static BranchSide empty() {
            return new BranchSide(null, List.of(), 0, 0, 0, 0, List.of(),
                    ApprovalStats.empty(), VerificationStats.empty(), PlanStats.empty(),
                    ChangeStats.empty(), List.of());
        }
    }

    /**
     * 审批统计。
     *
     * @param total 总数
     * @param denied 拒绝数
     * @param deniedTools 被拒绝的工具名
     */
    public record ApprovalStats(int total, int denied, List<String> deniedTools) {

        /**
         * 规范化列表。
         *
         * @param total 总数
         * @param denied 拒绝数
         * @param deniedTools 工具名
         */
        public ApprovalStats {
            deniedTools = deniedTools == null ? List.of() : List.copyOf(deniedTools);
        }

        /**
         * 空统计。
         *
         * @return 全为 0 的统计
         */
        public static ApprovalStats empty() {
            return new ApprovalStats(0, 0, List.of());
        }
    }

    /**
     * 验证统计。
     *
     * @param total 总数
     * @param failed 失败数
     * @param failedCommands 失败的命令
     */
    public record VerificationStats(int total, int failed, List<String> failedCommands) {

        /**
         * 规范化列表。
         *
         * @param total 总数
         * @param failed 失败数
         * @param failedCommands 命令
         */
        public VerificationStats {
            failedCommands = failedCommands == null ? List.of() : List.copyOf(failedCommands);
        }

        /**
         * 空统计。
         *
         * @return 全为 0 的统计
         */
        public static VerificationStats empty() {
            return new VerificationStats(0, 0, List.of());
        }
    }

    /**
     * 计划统计。
     *
     * @param total 条目总数
     * @param done 已完成数
     * @param unfinished 未完成条目
     */
    public record PlanStats(int total, int done, List<String> unfinished) {

        /**
         * 规范化列表。
         *
         * @param total 总数
         * @param done 完成数
         * @param unfinished 未完成条目
         */
        public PlanStats {
            unfinished = unfinished == null ? List.of() : List.copyOf(unfinished);
        }

        /**
         * 空统计。
         *
         * @return 全为 0 的统计
         */
        public static PlanStats empty() {
            return new PlanStats(0, 0, List.of());
        }
    }

    /**
     * 改动统计。
     *
     * @param turns 有改动的轮数
     * @param files 不同文件数
     * @param additions 新增行
     * @param deletions 删除行
     */
    public record ChangeStats(int turns, int files, int additions, int deletions) {

        /**
         * 空统计。
         *
         * @return 全为 0 的统计
         */
        public static ChangeStats empty() {
            return new ChangeStats(0, 0, 0, 0);
        }
    }

    /**
     * 单个文件的累计改动。
     *
     * @param path 路径
     * @param additions 新增行
     * @param deletions 删除行
     * @param changes 出现次数
     */
    public record FileSummary(String path, int additions, int deletions, int changes) {
    }

    /**
     * 路径级文件差异。
     *
     * @param onlyAbandoned 只在被放弃分支改过
     * @param onlyCurrent 只在当前分支改过
     * @param both 两边都改过
     * @param truncated 是否有列表因超限被截断
     */
    public record FileDifference(List<String> onlyAbandoned, List<String> onlyCurrent,
            List<String> both, boolean truncated) {

        /**
         * 规范化列表。
         *
         * @param onlyAbandoned 仅另一条分支
         * @param onlyCurrent 仅当前分支
         * @param both 两边都有
         * @param truncated 是否截断
         */
        public FileDifference {
            onlyAbandoned = onlyAbandoned == null ? List.of() : List.copyOf(onlyAbandoned);
            onlyCurrent = onlyCurrent == null ? List.of() : List.copyOf(onlyCurrent);
            both = both == null ? List.of() : List.copyOf(both);
        }

        /**
         * 空差异。
         *
         * @return 三组都为空的差异
         */
        public static FileDifference empty() {
            return new FileDifference(List.of(), List.of(), List.of(), false);
        }
    }
}