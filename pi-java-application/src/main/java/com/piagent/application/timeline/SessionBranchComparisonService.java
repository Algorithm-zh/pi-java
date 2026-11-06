package com.piagent.application.timeline;

import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 对比最近一次回退前后的两条分支。
 *
 * <p>只读：不要求会话空闲，不追加 entry，不访问磁盘。分叉点由两条分支的
 * entry id 实际前缀求得，不依赖简报 {@code details}。</p>
 */
@Service
public class SessionBranchComparisonService {

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final BranchComparisonProjector projector;

    /**
     * 创建对比用例。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param projector 单侧投影器
     */
    public SessionBranchComparisonService(SessionRepository sessions, SessionEntryStore entries,
            BranchComparisonProjector projector) {
        this.sessions = sessions;
        this.entries = entries;
        this.projector = projector;
    }

    /**
     * 生成当前分支与最近一次（或指定一次）回退前的对比。
     *
     * @param sessionId 会话
     * @param rewindEntryId 指定的回退简报；为 null 时取最近一次
     * @return 对比；没有回退时 available=false
     */
    public SessionBranchComparison compare(SessionId sessionId, EntryId rewindEntryId) {
        ChatSession session = require(sessionId);
        List<SessionEntry> current = entries.loadBranch(sessionId, session.leafEntryId());
        List<SessionEntry> briefs = current.stream()
                .filter(entry -> entry.payload() instanceof BranchSummaryPayload summary
                        && isRewind(summary))
                .toList();
        if (briefs.isEmpty()) {
            return SessionBranchComparison.unavailable();
        }
        SessionEntry brief = select(briefs, rewindEntryId);
        BranchSummaryPayload payload = (BranchSummaryPayload) brief.payload();
        List<SessionEntry> abandoned = entries.loadBranch(sessionId, payload.fromEntryId());
        int fork = commonPrefix(abandoned, current);
        SessionBranchComparison.BranchSide abandonedSide = projector.project(
                abandoned.subList(fork, abandoned.size()), payload.fromEntryId());
        SessionBranchComparison.BranchSide currentSide = projector.project(
                current.subList(fork, current.size()), session.leafEntryId());
        SessionBranchComparison.FileDifference files = projector.difference(abandonedSide, currentSide);
        EntryId forkEntryId = fork == 0 ? null : current.get(fork - 1).id();
        return new SessionBranchComparison(true, brief.id(), forkEntryId, brief.createdAt(),
                abandonedSide, currentSide, files,
                projector.summary(abandonedSide, currentSide, files));
    }

    private SessionEntry select(List<SessionEntry> briefs, EntryId rewindEntryId) {
        if (rewindEntryId == null) {
            return briefs.getLast();
        }
        return briefs.stream()
                .filter(entry -> entry.id().equals(rewindEntryId))
                .findFirst()
                .orElseThrow(() -> new DomainException(DomainErrorCode.INVALID_BRANCH,
                        "指定的回退简报不在当前分支上: " + rewindEntryId));
    }

    private int commonPrefix(List<SessionEntry> abandoned, List<SessionEntry> current) {
        int shared = Math.min(abandoned.size(), current.size());
        int index = 0;
        while (index < shared && abandoned.get(index).id().equals(current.get(index).id())) {
            index++;
        }
        return index;
    }

    private boolean isRewind(BranchSummaryPayload payload) {
        return "rewind".equals(payload.details().get("kind"));
    }

    private ChatSession require(SessionId sessionId) {
        return sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }
}