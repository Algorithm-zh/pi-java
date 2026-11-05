package com.piagent.application.timeline;

import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在空闲会话上追加回溯简报的追加器。
 *
 * <p>回退后旧 entry 不再位于当前分支；简报成为新分支的起点，回退第一轮时它是根 entry。
 * 单独成类是因为 {@code SessionEntryAppender} 已接近 Checkstyle 的文件行数上限。</p>
 */
@Service
public class BranchSummaryAppender {

    private static final EntryTokenCounter TOKEN_COUNTER = new EntryTokenCounter();

    private final SessionRepository repository;
    private final SessionEntryStore entryStore;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建追加器。
     *
     * @param repository 会话仓储
     * @param entryStore entry 存储
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public BranchSummaryAppender(SessionRepository repository, SessionEntryStore entryStore,
            IdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.entryStore = entryStore;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 追加一条分支摘要并把叶子移到该 entry。
     *
     * @param sessionId 会话标识
     * @param payload 分支摘要载荷
     * @return 新 entry
     */
    @Transactional
    public SessionEntry append(SessionId sessionId, BranchSummaryPayload payload) {
        Objects.requireNonNull(payload, "payload 不能为空");
        ChatSession session = repository.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
        Guard.require(session.status() == SessionStatus.IDLE,
                DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以追加回溯简报，当前状态: " + session.status());
        SessionEntry entry = build(session, payload);
        entryStore.append(entry);
        repository.update(session.moveLeaf(entry.id()));
        return entry;
    }

    private SessionEntry build(ChatSession session, BranchSummaryPayload payload) {
        long seq = entryStore.countBySession(session.identity().id()) + 1;
        int tokens = Math.max(1, TOKEN_COUNTER.countText(payload.summary()));
        EntryId id = new EntryId(Ulid.parse(idGenerator.newId()));
        return new SessionEntry(id, session.identity().id(), seq, session.leafEntryId(),
                payload.entryType(), SessionEntry.CURRENT_PAYLOAD_VERSION, payload, tokens,
                clock.instant());
    }
}
