package com.piagent.trigger.http.session;

import com.piagent.application.timeline.SessionRewindResult;
import com.piagent.domain.git.model.GitConflict;
import java.util.List;

/**
 * 回退预览或执行结果。
 *
 * @param dryRun 是否只是预览
 * @param rewound 是否已完成回退
 * @param restorable 工作区是否可以安全还原
 * @param files 计划还原的路径
 * @param restored 实际还原的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的回溯简报 entry
 * @param discardedEntries 被丢弃的 entry 数
 * @param discardedRuns 被丢弃的 run 数
 * @param brief 简报正文
 */
public record SessionRewindResponse(boolean dryRun, boolean rewound, boolean restorable,
        List<String> files, List<String> restored, List<ConflictResponse> conflicts,
        String briefEntryId, int discardedEntries, int discardedRuns, String brief) {

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
     * @param result 回退结果
     * @return 响应
     */
    public static SessionRewindResponse from(SessionRewindResult result) {
        return new SessionRewindResponse(result.dryRun(), result.rewound(),
                result.restorable(), result.files(), result.restored(),
                result.conflicts().stream().map(SessionRewindResponse::conflict).toList(),
                result.briefEntryId() == null ? null : result.briefEntryId().toString(),
                result.discardedEntries(), result.discardedRuns(), result.brief());
    }

    private static ConflictResponse conflict(GitConflict conflict) {
        return new ConflictResponse(conflict.path(), conflict.reason());
    }
}
