package com.piagent.application.git;

import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 撤销最近一次 run 的用例。
 *
 * <p>先把本轮改动过的路径还原到检查点树，再让会话分支退回用户消息之前的叶子。
 * 只要有一个文件在本轮之后又被修改过，就整体放弃：既不覆盖用户的新改动，也不
 * 让对话与工作区出现一半新一半旧的错位。</p>
 *
 * <p>撤销会话第一轮时叶子回到空分支；entry 日志必须同时清空，否则下一次追加
 * 会因为没有父节点而违反根 entry 约束。工作区还原与日志清理在同一事务内完成。</p>
 */
@Service
public class GitUndoService {

    private static final int MAX_ERROR_LENGTH = 400;

    private final GitCheckpointService checkpoints;
    private final WorkspaceGit git;
    private final SessionRepository sessions;
    private final SessionEntryStore entries;

    /**
     * 创建撤销服务。
     *
     * @param checkpoints 检查点服务
     * @param git 工作区 Git 端口
     * @param sessions 会话仓储
     * @param entries entry 存储
     */
    public GitUndoService(GitCheckpointService checkpoints, WorkspaceGit git,
            SessionRepository sessions, SessionEntryStore entries) {
        this.checkpoints = checkpoints;
        this.git = git;
        this.sessions = sessions;
        this.entries = entries;
    }

    /**
     * 撤销最近一次 run。
     *
     * @param sessionId 会话标识
     * @return 撤销结果；有冲突时 {@code rewound} 为 false 且工作区未修改
     */
    @Transactional
    public GitUndoResult undo(SessionId sessionId) {
        ChatSession session = require(sessionId);
        Guard.require(session.status() == SessionStatus.IDLE, DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以撤销上一轮，当前状态: " + session.status());
        SessionGitChanges changes = checkpoints.changes(sessionId);
        GitCheckpointPayload checkpoint = changes.checkpoint();
        if (checkpoint == null) {
            throw new DomainException(DomainErrorCode.GIT_UNAVAILABLE,
                    "最近一次运行没有可用的工作区检查点");
        }
        String latestTree = latestTree(checkpoint, changes.turns());
        GitRestoreResult restored = restore(session, checkpoint, latestTree);
        if (!restored.complete()) {
            return new GitUndoResult(List.of(), restored.conflicts(), false);
        }
        if (checkpoint.baseLeafId() == null) {
            entries.deleteAll(sessionId);
        }
        sessions.update(session.rewind(checkpoint.baseLeafId()));
        return new GitUndoResult(restored.restored(), List.of(), true);
    }

    private String latestTree(GitCheckpointPayload checkpoint, List<GitChangeSetPayload> turns) {
        String tree = checkpoint.baseTreeSha();
        for (GitChangeSetPayload turn : turns) {
            tree = turn.treeSha();
        }
        return tree;
    }

    private GitRestoreResult restore(ChatSession session, GitCheckpointPayload checkpoint,
            String latestTree) {
        try {
            return git.restore(session.config().cwd(), checkpoint.baseTreeSha(), latestTree);
        } catch (DomainException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "还原工作区失败: " + message(error));
        }
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }

    private String message(Throwable error) {
        String text = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }
}
