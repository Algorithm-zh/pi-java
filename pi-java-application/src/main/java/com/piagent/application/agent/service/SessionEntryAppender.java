package com.piagent.application.agent.service;

import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.session.service.SessionTitles;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import com.piagent.domain.tool.model.ToolResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 运行期的会话 entry 追加器。
 *
 * <p>每次追加都是一个短事务：先插入 entry，再以 CAS 推进 leaf 与 version。
 * LLM 与工具执行发生在事务之外，避免长事务占住数据库连接。会话状态在 run
 * 开始时置为 RUNNING，在写入 RUN_SUMMARY 的同一次事务里回到 IDLE。
 */
@Service
public class SessionEntryAppender {

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
    public SessionEntryAppender(SessionRepository repository, SessionEntryStore entryStore,
            IdGenerator idGenerator, Clock clock) {
        this.repository = repository;
        this.entryStore = entryStore;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 追加用户消息并把会话切到 RUNNING；空会话的首条消息顺带把占位标题换成消息摘要。
     *
     * @param sessionId 会话标识
     * @param text 用户文本
     * @return 新 entry
     */
    @Transactional
    public SessionEntry appendUserAndStart(SessionId sessionId, String text) {
        ChatSession session = require(sessionId);
        Guard.require(session.status() == SessionStatus.IDLE, DomainErrorCode.INVALID_STATE_TRANSITION,
                "只有空闲会话可以开始 run，当前状态: " + session.status());
        boolean firstEntry = entryStore.countBySession(sessionId) == 0;
        ChatSession running = session.markRunning();
        if (firstEntry && SessionTitles.isPlaceholder(session.title())) {
            String generated = SessionTitles.fromText(text);
            if (!generated.isBlank()) {
                running = running.rename(generated);
            }
        }
        Instant now = clock.instant();
        AgentMessage message = new UserMessage(List.of(new TextBlock(text)), now);
        SessionEntry entry = append(running, new MessagePayload(message), estimate(message), now);
        repository.update(running.advanceLeaf(entry.id()));
        return entry;
    }

    /**
     * 追加助手消息。
     *
     * @param sessionId 会话标识
     * @param message 助手消息
     * @return 新 entry
     */
    @Transactional
    public SessionEntry appendMessage(SessionId sessionId, AgentMessage message) {
        Objects.requireNonNull(message, "message 不能为空");
        return appendRunning(sessionId, new MessagePayload(message), estimate(message));
    }

    /**
     * 追加压缩摘要 entry：运行中用 advanceLeaf，空闲手动压缩用 moveLeaf，等待审批时拒绝压缩。
     *
     * @param sessionId 会话标识
     * @param payload 压缩载荷
     * @return 新 entry
     */
    @Transactional
    public SessionEntry appendCompaction(SessionId sessionId, CompactionPayload payload) {
        Objects.requireNonNull(payload, "payload 不能为空");
        ChatSession session = require(sessionId);
        Guard.require(session.status() != SessionStatus.WAITING_APPROVAL,
                DomainErrorCode.INVALID_STATE_TRANSITION, "等待审批时不能压缩会话");
        Instant now = clock.instant();
        int tokens = Math.max(1, TOKEN_COUNTER.countText(payload.summary()));
        SessionEntry entry = append(session, payload, tokens, now);
        ChatSession next = switch (session.status()) {
            case RUNNING -> session.advanceLeaf(entry.id());
            case IDLE -> session.moveLeaf(entry.id());
            default -> throw new DomainException(DomainErrorCode.INVALID_STATE_TRANSITION,
                    "当前状态不能压缩会话: " + session.status());
        };
        repository.update(next);
        return entry;
    }

    /**
     * 在同一事务中追加一批工具结果，按传入顺序形成父子链。
     *
     * @param sessionId 会话标识
     * @param results 工具结果
     */
    @Transactional
    public void appendToolResults(SessionId sessionId, List<ToolResult> results) {
        Objects.requireNonNull(results, "results 不能为空");
        if (results.isEmpty()) {
            return;
        }
        ChatSession session = require(sessionId);
        requireRunning(session);
        Instant now = clock.instant();
        ChatSession current = session;
        for (ToolResult result : results) {
            Objects.requireNonNull(result, "result 不能为空");
            ToolResultMessage message = new ToolResultMessage(result.callId().toString(),
                    result.tool().toString(), List.of(new TextBlock(result.content())),
                    result.error(), result.details(), now);
            SessionEntry entry = append(current, new MessagePayload(message), estimate(message), now);
            current = current.advanceLeaf(entry.id());
        }
        repository.update(current);
    }

    /**
     * 把运行中的会话切到等待审批。
     *
     * <p>重复调用是幂等的：前一个待办已决策、会话仍在等待下一个审批时，
     * 不再做无意义的状态迁移。</p>
     *
     * @param sessionId 会话标识
     */
    @Transactional
    public void markWaitingApproval(SessionId sessionId) {
        ChatSession session = require(sessionId);
        if (session.status() == SessionStatus.WAITING_APPROVAL) {
            return;
        }
        repository.update(session.markWaitingApproval());
    }

    /**
     * 追加审批决策 entry，并让会话回到运行中。
     *
     * @param sessionId 会话标识
     * @param payload 审批载荷
     * @return 新 entry
     */
    @Transactional
    public SessionEntry appendApproval(SessionId sessionId, ApprovalPayload payload) {
        Objects.requireNonNull(payload, "payload 不能为空");
        ChatSession session = require(sessionId);
        requireRunning(session);
        Instant now = clock.instant();
        int tokens = estimateText(payload.decision()
                + (payload.reason() == null ? "" : payload.reason()));
        SessionEntry entry = append(session, payload, tokens, now);
        ChatSession next = session.advanceLeaf(entry.id());
        if (session.status() == SessionStatus.WAITING_APPROVAL) {
            next = next.markRunning();
        }
        repository.update(next);
        return entry;
    }

    /**
     * 追加不进入模型上下文的运行期元数据 entry。
     *
     * <p>Git 检查点与逐轮改动只用于展示和撤销，token 估算固定为 0。</p>
     *
     * @param sessionId 会话标识
     * @param payload 元数据载荷
     * @return 新 entry
     */
    @Transactional
    public SessionEntry appendMetadata(SessionId sessionId, EntryPayload payload) {
        Objects.requireNonNull(payload, "payload 不能为空");
        return appendRunning(sessionId, payload, 0);
    }

    /**
     * 追写 RUN_SUMMARY 并把会话恢复为空闲。
     *
     * @param sessionId 会话标识
     * @param payload 运行摘要
     * @return 摘要 entry
     */
    @Transactional
    public SessionEntry finishRun(SessionId sessionId, RunSummaryPayload payload) {
        ChatSession session = require(sessionId);
        requireRunning(session);
        Instant now = clock.instant();
        int tokens = estimateText(payload.stopReason()
                + (payload.errorMessage() == null ? "" : payload.errorMessage()));
        SessionEntry entry = append(session, payload, tokens, now);
        ChatSession idle = session.advanceLeaf(entry.id()).markIdle();
        repository.update(idle);
        return entry;
    }

    /**
     * 持久化失败后的兜底：尽力把会话恢复为空闲。
     *
     * @param sessionId 会话标识
     */
    @Transactional
    public void forceIdle(SessionId sessionId) {
        repository.findById(sessionId).ifPresent(session -> {
            if (session.status() == SessionStatus.RUNNING
                    || session.status() == SessionStatus.WAITING_APPROVAL) {
                repository.update(session.markIdle());
            }
        });
    }

    private SessionEntry appendRunning(SessionId sessionId, EntryPayload payload, int tokenEstimate) {
        ChatSession session = require(sessionId);
        requireRunning(session);
        Instant now = clock.instant();
        SessionEntry entry = append(session, payload, tokenEstimate, now);
        repository.update(session.advanceLeaf(entry.id()));
        return entry;
    }

    private SessionEntry append(ChatSession session, EntryPayload payload, int tokenEstimate, Instant now) {
        long seq = entryStore.countBySession(session.identity().id()) + 1;
        SessionEntry entry = new SessionEntry(nextEntryId(), session.identity().id(), seq,
                session.leafEntryId(), payload.entryType(), SessionEntry.CURRENT_PAYLOAD_VERSION,
                payload, tokenEstimate, now);
        entryStore.append(entry);
        return entry;
    }

    private ChatSession require(SessionId sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
    }

    private void requireRunning(ChatSession session) {
        Guard.require(session.status() == SessionStatus.RUNNING
                        || session.status() == SessionStatus.WAITING_APPROVAL,
                DomainErrorCode.INVALID_STATE_TRANSITION,
                "会话没有活跃 run，当前状态: " + session.status());
    }

    private EntryId nextEntryId() {
        return new EntryId(Ulid.parse(idGenerator.newId()));
    }

    /**
     * 估算消息 token；M7 起与投影、压缩共用 {@link EntryTokenCounter}，避免中文被低估。
     */
    private int estimate(AgentMessage message) {
        return TOKEN_COUNTER.countMessage(message);
    }

    private int estimateText(String text) {
        return Math.max(1, TOKEN_COUNTER.countText(text));
    }
}
