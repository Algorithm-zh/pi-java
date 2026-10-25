package com.piagent.trigger.http.session;

import com.piagent.application.git.SessionGitChanges;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import java.util.List;

/**
 * 最近一次 run 的工作区改动响应。
 *
 * <p>{@code available=false} 表示当前目录不是 Git 仓库或 Git 已关闭；前端据此
 * 隐藏改动面板，而不是显示一个空面板。{@code canUndo} 由会话状态与检查点共同决定。</p>
 *
 * @param available 是否有 Git 信息
 * @param canUndo 是否允许撤销
 * @param branch 检查点所在分支；游离 HEAD 或空仓库时为 null
 * @param headSha 检查点时的 HEAD；可能为 null
 * @param fileCount 涉及文件数
 * @param additions 新增行总数
 * @param deletions 删除行总数
 * @param suggestedCommit 最后一轮的提交建议
 * @param turns 逐轮改动
 */
public record SessionGitChangesResponse(
        boolean available,
        boolean canUndo,
        String branch,
        String headSha,
        int fileCount,
        int additions,
        int deletions,
        String suggestedCommit,
        List<TurnResponse> turns) {

    /**
     * 单轮改动。
     *
     * @param turn 轮次
     * @param files 文件列表
     * @param diff 统一 diff 文本
     * @param truncated 是否截断
     * @param suggestedCommit 提交建议
     */
    public record TurnResponse(int turn, List<FileResponse> files, String diff, boolean truncated,
            String suggestedCommit) {
    }

    /**
     * 单个文件的变化。
     *
     * @param path 路径
     * @param kind 变化类型
     * @param additions 新增行
     * @param deletions 删除行
     * @param binary 是否二进制
     */
    public record FileResponse(String path, String kind, int additions, int deletions,
            boolean binary) {
    }

    /**
     * 从应用层汇总生成响应。
     *
     * @param changes 改动汇总
     * @return 响应
     */
    public static SessionGitChangesResponse from(SessionGitChanges changes) {
        List<TurnResponse> turns = changes.turns().stream()
                .map(SessionGitChangesResponse::turn)
                .toList();
        String suggested = turns.isEmpty() ? "" : turns.getLast().suggestedCommit();
        return new SessionGitChangesResponse(changes.available(), changes.canUndo(),
                changes.available() ? changes.checkpoint().branch() : null,
                changes.available() ? changes.checkpoint().headSha() : null,
                changes.fileCount(), changes.additions(), changes.deletions(), suggested, turns);
    }

    private static TurnResponse turn(GitChangeSetPayload payload) {
        return new TurnResponse(payload.turn(), payload.files().stream()
                .map(SessionGitChangesResponse::file).toList(), payload.diff(),
                payload.truncated(), payload.suggestedCommit());
    }

    private static FileResponse file(FileChange change) {
        return new FileResponse(change.path(), change.kind().name(), change.additions(),
                change.deletions(), change.binary());
    }
}
