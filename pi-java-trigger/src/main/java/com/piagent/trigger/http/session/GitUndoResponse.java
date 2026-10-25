package com.piagent.trigger.http.session;

import com.piagent.application.git.GitUndoResult;
import com.piagent.domain.git.model.GitConflict;
import java.util.List;

/**
 * 撤销上一轮的响应。
 *
 * <p>存在冲突时仍返回 200，但 {@code rewound=false}：调用方需要展示冲突原因，
 * 让用户自己决定是否处理，而不是只看到一句“撤销失败”。</p>
 *
 * @param rewound 是否已退回会话分支并还原工作区
 * @param restored 已还原的路径
 * @param conflicts 冲突列表
 */
public record GitUndoResponse(boolean rewound, List<String> restored,
        List<ConflictResponse> conflicts) {

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
     * @param result 撤销结果
     * @return 响应
     */
    public static GitUndoResponse from(GitUndoResult result) {
        return new GitUndoResponse(result.rewound(), result.restored(),
                result.conflicts().stream().map(GitUndoResponse::conflict).toList());
    }

    private static ConflictResponse conflict(GitConflict conflict) {
        return new ConflictResponse(conflict.path(), conflict.reason());
    }
}
