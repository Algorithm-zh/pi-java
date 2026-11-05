package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回退到任意检查点的用例。
 *
 * <p>目标树直接取检查点的 {@code baseTreeSha}；当前分支上最后一个已知树来自
 * 之后的改动或下一个检查点的起点。还原前先用只读计划检查冲突，冲突时一个字节
 * 都不写、一条 entry 都不删。成功后分支退回检查点记录的叶子，并追加一条回溯
 * 简报作为新分支的起点。被丢弃的 entry 保留在会话日志中，只是不再属于当前分支；
 * 简报的 {@code fromEntryId} 指向旧叶子，可用于只读回看。</p>
 *
 * <p>叶子回退与简报追加先于工作区写入，二者处于同一事务；数据库步骤失败时事务
 * 回滚，工作区尚未改动，不会出现“文件已还原但对话没退”的半完成状态。工作区写入
 * 失败则抛异常回滚事务；执行前的二次检查若发现冲突，工作区同样不会写入。git 按
 * 文件逐个写入，若写入中途失败，事务会回滚会话，但工作区可能留下部分已写文件。</p>
 */
@Service
public class SessionRewindService {

    private static final int MAX_ERROR_LENGTH = 400;

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final WorkspaceGit git;
    private final BranchSummaryAppender summaries;
    private final RewindBriefBuilder briefs;

    /**
     * 创建回退服务。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param git 工作区 Git 端口
     * @param summaries 回溯简报追加器
     * @param briefs 简报生成器
     */
    public SessionRewindService(SessionRepository sessions, SessionEntryStore entries,
            WorkspaceGit git, BranchSummaryAppender summaries, RewindBriefBuilder briefs) {
        this.sessions = sessions;
        this.entries = entries;
        this.git = git;
        this.summaries = summaries;
        this.briefs = briefs;
    }

    /**
     * 预览或执行一次回退。
     *
     * @param sessionId 会话标识
     * @param checkpointEntryId 目标检查点 entry
     * @param dryRun true 表示只预览
     * @param note 用户备注，可为 null
     * @return 预览或执行结果
     */
    @Transactional
    public SessionRewindResult rewind(SessionId sessionId, EntryId checkpointEntryId,
            boolean dryRun, String note) {
        Objects.requireNonNull(checkpointEntryId, "checkpointEntryId 不能为空");
        ChatSession session = require(sessionId);
        if (session.status() == SessionStatus.ARCHIVED) {
            throw new DomainException(DomainErrorCode.SESSION_ARCHIVED, "已归档会话不能回退");
        }
        if (session.status() != SessionStatus.IDLE) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "只有空闲会话可以回退，当前状态: " + session.status());
        }
        List<SessionEntry> branch = entries.loadBranch(sessionId, session.leafEntryId());
        int index = indexOf(branch, checkpointEntryId);
        if (index < 0 || !(branch.get(index).payload() instanceof GitCheckpointPayload checkpoint)) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "目标不是当前分支上的检查点: " + checkpointEntryId);
        }
        SessionEntry checkpointEntry = branch.get(index);
        int discardStart = discardStart(branch, checkpoint.baseLeafId());
        List<SessionEntry> discarded = List.copyOf(branch.subList(discardStart, branch.size()));
        String latestTree = latestTree(branch, index, checkpoint.baseTreeSha());
        GitRestorePlan plan = plan(session, checkpoint.baseTreeSha(), latestTree);
        RewindBrief preview = briefs.build(checkpointEntry, discarded, plan.files(), note);
        if (dryRun) {
            return new SessionRewindResult(true, false, plan.restorable(), plan.files(), List.of(),
                    plan.conflicts(), null, discarded.size(), countRuns(discarded),
                    preview.summary());
        }
        if (!plan.restorable()) {
            return conflict(plan);
        }
        EntryId oldLeaf = session.leafEntryId();
        sessions.update(session.rewind(checkpoint.baseLeafId()));
        SessionEntry briefEntry = summaries.append(sessionId,
                new BranchSummaryPayload(oldLeaf, preview.summary(), preview.details()));
        GitRestoreResult restored = restore(session, checkpoint.baseTreeSha(), latestTree);
        if (!restored.complete()) {
            // 预检后工作区又被改动：restore 未写任何文件，抛异常回滚叶子与简报。
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "确认后工作区又发生了变化，已放弃本次回退，请重试");
        }
        return new SessionRewindResult(false, true, true, plan.files(), restored.restored(),
                List.of(), briefEntry.id(), discarded.size(), countRuns(discarded),
                preview.summary());
    }

    private SessionRewindResult conflict(GitRestorePlan plan) {
        return new SessionRewindResult(false, false, false, plan.files(), List.of(),
                plan.conflicts(), null, 0, 0, "");
    }

    private int indexOf(List<SessionEntry> branch, EntryId checkpointEntryId) {
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).id().equals(checkpointEntryId)) {
                return index;
            }
        }
        return -1;
    }

    private int discardStart(List<SessionEntry> branch, EntryId baseLeafId) {
        if (baseLeafId == null) {
            return 0;
        }
        int index = indexOf(branch, baseLeafId);
        if (index < 0) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "检查点的起点不在当前分支: " + baseLeafId);
        }
        return index + 1;
    }

    private String latestTree(List<SessionEntry> branch, int checkpointIndex, String fallback) {
        String latest = fallback;
        for (int index = checkpointIndex + 1; index < branch.size(); index++) {
            Object payload = branch.get(index).payload();
            if (payload instanceof GitChangeSetPayload change) {
                latest = change.treeSha();
            } else if (payload instanceof GitCheckpointPayload next) {
                latest = next.baseTreeSha();
            }
        }
        return latest;
    }

    private int countRuns(List<SessionEntry> discarded) {
        return (int) discarded.stream()
                .filter(entry -> entry.payload() instanceof GitCheckpointPayload).count();
    }

    private GitRestorePlan plan(ChatSession session, String targetTree, String latestTree) {
        try {
            return git.planRestore(session.config().cwd(), targetTree, latestTree);
        } catch (DomainException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "读取还原计划失败: " + message(error));
        }
    }

    private GitRestoreResult restore(ChatSession session, String targetTree, String latestTree) {
        try {
            return git.restore(session.config().cwd(), targetTree, latestTree);
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
