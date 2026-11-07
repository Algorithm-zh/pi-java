package com.piagent.trigger.http.session;

import com.piagent.application.timeline.BranchResumeResult;
import com.piagent.domain.git.model.GitConflict;
import java.util.List;

/**
 * 分支切换预览或执行结果。
 *
 * @param dryRun 是否只是预览
 * @param switched 是否已完成切换
 * @param available 两侧树是否都可用
 * @param restorable 工作区是否可以安全还原
 * @param unavailableReason 不可用原因
 * @param targetLeafId 目标分支叶子
 * @param files 计划改动的路径
 * @param restored 实际改动的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的切换简报；预览或失败时为 null
 * @param leftEntries 离开侧 entry 数
 * @param leftRuns 离开侧 run 数
 * @param targetEntries 目标侧 entry 数
 * @param targetRuns 目标侧 run 数
 * @param brief 简报正文
 */
public record SessionResumeResponse(boolean dryRun, boolean switched, boolean available,
        boolean restorable, String unavailableReason, String targetLeafId, List<String> files,
        List<String> restored, List<ConflictResponse> conflicts, String briefEntryId,
        int leftEntries, int leftRuns, int targetEntries, int targetRuns, String brief) {

    /**
     * 冲突文件。
     *
     * @param path 路径
     * @param reason 原因
     */
    public record ConflictResponse(String path, String reason) {
    }

    /**
     * 从应用层结果生成响应。
     *
     * @param result 切换结果
     * @return 响应
     */
    public static SessionResumeResponse from(BranchResumeResult result) {
        return new SessionResumeResponse(result.dryRun(), result.switched(), result.available(),
                result.restorable(), result.unavailableReason(),
                result.targetLeafId() == null ? null : result.targetLeafId().toString(),
                result.files(), result.restored(),
                result.conflicts().stream().map(SessionResumeResponse::conflict).toList(),
                result.briefEntryId() == null ? null : result.briefEntryId().toString(),
                result.leftEntries(), result.leftRuns(), result.targetEntries(),
                result.targetRuns(), result.brief());
    }

    private static ConflictResponse conflict(GitConflict conflict) {
        return new ConflictResponse(conflict.path(), conflict.reason());
    }
}
