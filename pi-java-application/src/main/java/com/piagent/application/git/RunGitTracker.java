package com.piagent.application.git;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 单个 run 的 Git 跟踪器。
 *
 * <p>只保存“上一轮结束时的树对象”，避免每轮重复快照整个仓库：本轮起点就是上一轮
 * 终点，第一轮起点是 run 检查点。跟踪器不持有会话或仓储引用，run 结束后即可丢弃。</p>
 */
public final class RunGitTracker {

    private final GitCheckpointService checkpoints;
    private final SessionId sessionId;
    private final Path workspace;
    private final RunId runId;
    private final EntryId baseLeafId;
    private String treeSha;

    /**
     * 创建跟踪器。
     *
     * @param checkpoints 检查点服务
     * @param sessionId 会话标识
     * @param workspace 工作目录
     * @param runId run 标识
     * @param baseLeafId 用户消息之前的叶子
     */
    public RunGitTracker(GitCheckpointService checkpoints, SessionId sessionId, Path workspace,
            RunId runId, EntryId baseLeafId) {
        this.checkpoints = checkpoints;
        this.sessionId = sessionId;
        this.workspace = workspace;
        this.runId = runId;
        this.baseLeafId = baseLeafId;
    }

    /**
     * 捕获 run 起点快照；不是 Git 仓库时后续调用全部变成空操作。
     */
    public void begin() {
        treeSha = checkpoints.beginRun(sessionId, workspace, runId, baseLeafId)
                .map(snapshot -> snapshot.treeSha())
                .orElse(null);
    }

    /**
     * 记录一轮结束后的改动。
     *
     * @param turn 轮次
     * @param turnBaseLeaf 本轮开始前的叶子
     * @return 改动载荷；未启用、无变化或失败时为空
     */
    public Optional<GitChangeSetPayload> afterTurn(int turn, EntryId turnBaseLeaf) {
        if (treeSha == null) {
            return Optional.empty();
        }
        Optional<GitChangeSetPayload> changes = checkpoints.endTurn(sessionId, workspace, runId,
                turn, turnBaseLeaf, treeSha);
        if (changes.isPresent()) {
            treeSha = changes.get().treeSha();
        }
        return changes;
    }
}
