package com.piagent.application.git;

import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.git.service.CommitMessageSuggester;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Git 检查点用例服务：run 开始快照、逐轮 diff 与会话改动查询。
 *
 * <p>所有 Git 失败都降级为“没有 Git 信息”，绝不打断 run：仓库不可用、文件被占用
 * 或 git 超时都只记日志。检查点与改动作为普通 entry 进入会话日志，因此撤销不依赖
 * 内存状态，重启后仍可用。</p>
 */
@Service
public class GitCheckpointService {

    private static final System.Logger LOG = System.getLogger(GitCheckpointService.class.getName());

    private final WorkspaceGit git;
    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final SessionEntryAppender appender;

    /**
     * 创建服务。
     *
     * @param git 工作区 Git 端口
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param appender entry 追加器
     */
    public GitCheckpointService(WorkspaceGit git, SessionRepository sessions,
            SessionEntryStore entries, SessionEntryAppender appender) {
        this.git = git;
        this.sessions = sessions;
        this.entries = entries;
        this.appender = appender;
    }

    /**
     * 在 run 开始时捕获快照并写入检查点 entry。
     *
     * @param sessionId 会话标识
     * @param workspace 工作目录
     * @param runId run 标识
     * @param baseLeafId 用户消息之前的叶子；首条消息时为 null
     * @return 快照；不是 Git 仓库或快照失败时为空
     */
    public Optional<GitSnapshot> beginRun(SessionId sessionId, Path workspace, RunId runId,
            EntryId baseLeafId) {
        Optional<GitSnapshot> snapshot = snapshot(workspace);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        GitSnapshot value = snapshot.get();
        try {
            appender.appendMetadata(sessionId, new GitCheckpointPayload(runId.toString(),
                    baseLeafId, value.treeSha(), value.headSha(), value.branch()));
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "写入 Git 检查点失败: " + sessionId, error);
        }
        return snapshot;
    }

    /**
     * 在一轮结束（含工具执行）后计算相对本轮起点的改动。
     *
     * <p>没有变化时不写 entry，避免历史里出现大量空记录。</p>
     *
     * @param sessionId 会话标识
     * @param workspace 工作目录
     * @param runId run 标识
     * @param turn 轮次，从 1 开始
     * @param baseLeafId 本轮开始前的叶子
     * @param baseTreeSha 本轮开始前的树对象
     * @return 改动载荷；无变化或失败时为空
     */
    public Optional<GitChangeSetPayload> endTurn(SessionId sessionId, Path workspace, RunId runId,
            int turn, EntryId baseLeafId, String baseTreeSha) {
        Optional<GitSnapshot> snapshot = snapshot(workspace);
        if (snapshot.isEmpty() || snapshot.get().treeSha().equals(baseTreeSha)) {
            return Optional.empty();
        }
        String treeSha = snapshot.get().treeSha();
        GitChangeSet changeSet;
        try {
            changeSet = git.changes(workspace, baseTreeSha, treeSha);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "计算工作区改动失败: " + sessionId, error);
            return Optional.empty();
        }
        if (changeSet.isEmpty()) {
            return Optional.empty();
        }
        GitChangeSetPayload payload = new GitChangeSetPayload(runId.toString(), turn, baseLeafId,
                changeSet.baseTreeSha(), changeSet.treeSha(), changeSet.files(), changeSet.diff(),
                changeSet.truncated(), CommitMessageSuggester.suggest(changeSet.files()));
        try {
            appender.appendMetadata(sessionId, payload);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "写入工作区改动失败: " + sessionId, error);
        }
        return Optional.of(payload);
    }

    /**
     * 查询最近一次 run 的检查点与逐轮改动。
     *
     * @param sessionId 会话标识
     * @return 汇总；会话没有 Git entry 时返回 {@link SessionGitChanges#EMPTY}
     */
    public SessionGitChanges changes(SessionId sessionId) {
        ChatSession session = require(sessionId);
        List<SessionEntry> branch = entries.loadBranch(sessionId, session.leafEntryId());
        for (int index = branch.size() - 1; index >= 0; index--) {
            if (branch.get(index).payload() instanceof GitCheckpointPayload checkpoint) {
                List<GitChangeSetPayload> turns = turnsOfRun(branch, index, checkpoint.runId());
                return new SessionGitChanges(checkpoint, turns,
                        session.status() == SessionStatus.IDLE);
            }
        }
        return SessionGitChanges.EMPTY;
    }

    private List<GitChangeSetPayload> turnsOfRun(List<SessionEntry> branch, int checkpointIndex,
            String runId) {
        List<GitChangeSetPayload> turns = new ArrayList<>();
        for (int index = checkpointIndex + 1; index < branch.size(); index++) {
            if (branch.get(index).payload() instanceof GitChangeSetPayload change
                    && change.runId().equals(runId)) {
                turns.add(change);
            }
        }
        return List.copyOf(turns);
    }

    private Optional<GitSnapshot> snapshot(Path workspace) {
        try {
            return git.snapshot(workspace);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING, "捕获 Git 快照失败: " + workspace, error);
            return Optional.empty();
        }
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }
}
