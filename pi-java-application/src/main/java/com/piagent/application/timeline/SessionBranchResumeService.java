package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
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
 * 切到同一会话的另一条已知分支。
 *
 * <p>切换只移动叶子，不复制 entry；新简报的 {@code fromEntryId} 指向刚离开的叶子，
 * 因此沿同一条简报可以原路切回。目标树优先取发起简报的 {@code fromTreeSha}，
 * 旧数据没有时沿目标分支回退查找。任何冲突都整体放弃，不写叶子、不写工作区。</p>
 */
@Service
public class SessionBranchResumeService {

    private static final int MAX_ERROR_LENGTH = 400;
    private static final System.Logger LOG =
            System.getLogger(SessionBranchResumeService.class.getName());

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final WorkspaceGit git;
    private final BranchSummaryAppender summaries;
    private final BranchResumeBriefBuilder briefs;

    /**
     * 创建切换服务。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param git 工作区 Git 端口
     * @param summaries 分支简报追加器
     * @param briefs 切换简报生成器
     */
    public SessionBranchResumeService(SessionRepository sessions, SessionEntryStore entries,
            WorkspaceGit git, BranchSummaryAppender summaries, BranchResumeBriefBuilder briefs) {
        this.sessions = sessions;
        this.entries = entries;
        this.git = git;
        this.summaries = summaries;
        this.briefs = briefs;
    }

    /**
     * 预览或执行一次分支切换。
     *
     * @param sessionId 会话标识
     * @param branchEntryId 当前分支上的分支简报
     * @param dryRun true 表示只预览
     * @param note 用户备注，可为 null
     * @return 预览或执行结果
     */
    @Transactional
    public BranchResumeResult resume(SessionId sessionId, EntryId branchEntryId,
            boolean dryRun, String note) {
        Objects.requireNonNull(branchEntryId, "branchEntryId 不能为空");
        ChatSession session = require(sessionId);
        if (session.status() == SessionStatus.ARCHIVED) {
            throw new DomainException(DomainErrorCode.SESSION_ARCHIVED, "已归档会话不能切换分支");
        }
        if (session.status() != SessionStatus.IDLE) {
            throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "只有空闲会话可以切换分支，当前状态: " + session.status());
        }
        briefs.validateNote(note);
        List<SessionEntry> current = entries.loadBranch(sessionId, session.leafEntryId());
        int briefIndex = indexOf(current, branchEntryId);
        if (briefIndex < 0) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "切换点不在当前分支上: " + branchEntryId);
        }
        SessionEntry briefEntry = current.get(briefIndex);
        if (!(briefEntry.payload() instanceof BranchSummaryPayload payload)
                || !isBranch(payload)) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "目标不是分支简报: " + branchEntryId);
        }
        EntryId targetLeaf = payload.fromEntryId();
        List<SessionEntry> target = loadTarget(sessionId, targetLeaf);
        if (target.isEmpty() || indexOf(current, targetLeaf) >= 0) {
            throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                    "目标叶子不在另一条分支上: " + targetLeaf);
        }
        int fork = commonPrefix(current, target);
        List<SessionEntry> left = List.copyOf(current.subList(fork, current.size()));
        List<SessionEntry> targetSegment = List.copyOf(target.subList(fork, target.size()));
        int leftRuns = countRuns(left);
        int targetRuns = countRuns(targetSegment);

        String targetTree = text(payload.details().get("fromTreeSha"));
        if (targetTree == null) {
            targetTree = BranchTrees.treeOf(target);
        }
        String currentTree = BranchTrees.treeOf(current);
        if (targetTree == null || currentTree == null) {
            String reason = targetTree == null
                    ? "目标分支没有可用的工作区快照，无法安全切换；请先在目标分支建立一次检查点"
                    : "当前分支没有可用的工作区快照，无法安全切换；请先完成一轮对话";
            return new BranchResumeResult(dryRun, false, false, false, reason, targetLeaf,
                    List.of(), List.of(), List.of(), null, left.size(), leftRuns,
                    targetSegment.size(), targetRuns, "");
        }
        GitRestorePlan plan;
        try {
            plan = git.planRestore(session.config().cwd(), targetTree, currentTree);
        } catch (RuntimeException error) {
            return new BranchResumeResult(dryRun, false, false, false,
                    "读取还原计划失败：" + message(error), targetLeaf, List.of(), List.of(),
                    List.of(), null, left.size(), leftRuns, targetSegment.size(), targetRuns, "");
        }
        BranchResumeBrief preview = briefs.build(branchEntryId, left, targetSegment,
                plan.files(), currentTree, targetTree, note);
        if (dryRun) {
            return new BranchResumeResult(true, false, true, plan.restorable(), "", targetLeaf,
                    plan.files(), List.of(), plan.conflicts(), null, left.size(), leftRuns,
                    targetSegment.size(), targetRuns, preview.summary());
        }
        if (!plan.restorable()) {
            return new BranchResumeResult(false, false, true, false, "", targetLeaf,
                    plan.files(), List.of(), plan.conflicts(), null, left.size(), leftRuns,
                    targetSegment.size(), targetRuns, preview.summary());
        }

        String fromTree = snapshotTree(session, currentTree);
        BranchResumeBrief live = briefs.build(branchEntryId, left, targetSegment,
                plan.files(), fromTree, targetTree, note);
        EntryId oldLeaf = session.leafEntryId();
        sessions.update(session.moveLeaf(targetLeaf));
        SessionEntry brief = summaries.append(sessionId,
                new BranchSummaryPayload(oldLeaf, live.summary(), live.details()));
        GitRestoreResult restored = restore(session, targetTree, currentTree);
        if (!restored.complete()) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "确认后工作区又发生了变化，已放弃本次切换，请重试");
        }
        pin(session, oldLeaf, fromTree);
        pin(session, targetLeaf, targetTree);
        return new BranchResumeResult(false, true, true, true, "", targetLeaf, plan.files(),
                restored.restored(), List.of(), brief.id(), left.size(), leftRuns,
                targetSegment.size(), targetRuns, live.summary());
    }

    private List<SessionEntry> loadTarget(SessionId sessionId, EntryId targetLeaf) {
        if (targetLeaf == null) {
            return List.of();
        }
        try {
            return entries.loadBranch(sessionId, targetLeaf);
        } catch (DomainException error) {
            if (error.code() == DomainErrorCode.ENTRY_NOT_FOUND) {
                throw new DomainException(DomainErrorCode.INVALID_BRANCH,
                        "目标叶子不存在或不属于本会话: " + targetLeaf);
            }
            throw error;
        }
    }

    private String snapshotTree(ChatSession session, String fallback) {
        try {
            return git.snapshot(session.config().cwd())
                    .map(GitSnapshot::treeSha)
                    .filter(tree -> !tree.isBlank())
                    .orElse(fallback);
        } catch (RuntimeException error) {
            return fallback;
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

    private void pin(ChatSession session, EntryId leaf, String treeSha) {
        if (leaf == null || treeSha == null || treeSha.isBlank()) {
            return;
        }
        try {
            git.pinCheckpoint(session.config().cwd(), session.identity().id().toString(),
                    "branch-" + leaf, treeSha);
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.WARNING,
                    "保活分支树失败: " + session.identity().id(), error);
        }
    }

    private boolean isBranch(BranchSummaryPayload payload) {
        Object kind = payload.details().get("kind");
        return "rewind".equals(kind) || "resume".equals(kind);
    }

    private int indexOf(List<SessionEntry> branch, EntryId entryId) {
        for (int index = 0; index < branch.size(); index++) {
            if (branch.get(index).id().equals(entryId)) {
                return index;
            }
        }
        return -1;
    }

    private int commonPrefix(List<SessionEntry> left, List<SessionEntry> right) {
        int shared = Math.min(left.size(), right.size());
        int index = 0;
        while (index < shared && left.get(index).id().equals(right.get(index).id())) {
            index++;
        }
        return index;
    }

    private int countRuns(List<SessionEntry> segment) {
        return (int) segment.stream()
                .filter(entry -> entry.payload() instanceof GitCheckpointPayload).count();
    }

    private String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
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
