package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.EntryId;
import java.util.List;

/**
 * 一次分支切换请求的结果，预览与执行共用。
 *
 * @param dryRun 是否只是预览
 * @param switched 是否已完成切换
 * @param available 目标树与当前树是否都可用
 * @param restorable 工作区是否可以安全还原
 * @param unavailableReason 不可用原因；可用时为空串
 * @param targetLeafId 目标分支叶子
 * @param files 计划改动的工作区路径
 * @param restored 实际写入或删除的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的切换简报；预览或失败时为 null
 * @param leftEntries 离开分支尾段的 entry 数
 * @param leftRuns 离开分支尾段的 run 数
 * @param targetEntries 目标分支尾段的 entry 数
 * @param targetRuns 目标分支尾段的 run 数
 * @param brief 简报正文；预览也返回
 */
public record BranchResumeResult(boolean dryRun, boolean switched, boolean available,
        boolean restorable, String unavailableReason, EntryId targetLeafId, List<String> files,
        List<String> restored, List<GitConflict> conflicts, EntryId briefEntryId,
        int leftEntries, int leftRuns, int targetEntries, int targetRuns, String brief) {

    /**
     * 规范化列表与文本字段。
     *
     * @param dryRun 是否只是预览
     * @param switched 是否已完成切换
     * @param available 树是否可用
     * @param restorable 是否可以安全还原
     * @param unavailableReason 不可用原因
     * @param targetLeafId 目标叶子
     * @param files 计划路径
     * @param restored 实际路径
     * @param conflicts 冲突
     * @param briefEntryId 简报 entry
     * @param leftEntries 离开侧 entry 数
     * @param leftRuns 离开侧 run 数
     * @param targetEntries 目标侧 entry 数
     * @param targetRuns 目标侧 run 数
     * @param brief 简报正文
     */
    public BranchResumeResult {
        unavailableReason = unavailableReason == null ? "" : unavailableReason;
        files = files == null ? List.of() : List.copyOf(files);
        restored = restored == null ? List.of() : List.copyOf(restored);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        brief = brief == null ? "" : brief;
    }
}
