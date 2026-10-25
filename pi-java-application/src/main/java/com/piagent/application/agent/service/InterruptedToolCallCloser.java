package com.piagent.application.agent.service;

import com.piagent.domain.context.EntryTokenCounter;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.port.SessionEntryStore;
import com.piagent.domain.session.port.SessionRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为中断后悬空的工具调用补写错误结果。
 *
 * <p>run 只存在于进程内存，崩溃或强杀后日志会停在“已经发出工具调用、还没有
 * 任何结果”的位置。上下文投影与后续发送都会拒绝悬空调用，只把会话状态改回
 * IDLE 并不够。这里按配对规则找出悬空调用，在发起调用的 assistant entry 下
 * 逐条追写错误结果并让叶子指向最后一条。</p>
 *
 * <p>旧版本只复位状态、不闭合调用，用户随后发送的消息会落在悬空调用之后，
 * 形成无法通过“末尾追加”修复的分支。此时把叶子挂回调用处会丢弃这些失败的
 * 后续消息，但这是让会话继续可用的唯一方式；历史 entry 本身仍保留在库中。</p>
 */
@Service
public class InterruptedToolCallCloser {

    private static final EntryTokenCounter TOKEN_COUNTER = new EntryTokenCounter();
    private static final String INTERRUPTED_TEXT = "服务端重启，工具调用已中断，请重新发起。";

    private final SessionRepository sessions;
    private final SessionEntryStore entries;
    private final IdGenerator idGenerator;
    private final Clock clock;

    /**
     * 创建闭合器。
     *
     * @param sessions 会话仓储
     * @param entries entry 存储
     * @param idGenerator 标识生成器
     * @param clock 时钟
     */
    public InterruptedToolCallCloser(SessionRepository sessions, SessionEntryStore entries,
            IdGenerator idGenerator, Clock clock) {
        this.sessions = sessions;
        this.entries = entries;
        this.idGenerator = idGenerator;
        this.clock = clock;
    }

    /**
     * 闭合指定会话当前分支上的悬空工具调用。
     *
     * <p>会话可能已是 IDLE（旧版本恢复只复位了状态），因此内部会临时进入
     * RUNNING 追加结果，再回到 IDLE，整个过程在同一事务内完成。已归档会话
     * 不追加任何内容。</p>
     *
     * @param sessionId 会话标识
     * @return 补写的结果条数
     */
    @Transactional
    public int closeDangling(SessionId sessionId) {
        ChatSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new DomainException(DomainErrorCode.SESSION_NOT_FOUND,
                        "会话不存在: " + sessionId));
        Guard.require(session.status() != SessionStatus.ARCHIVED, DomainErrorCode.SESSION_ARCHIVED,
                "已归档会话没有可恢复的工具调用");
        if (session.isEmpty()) {
            return 0;
        }
        Map<String, PendingCall> dangling = stranded(entries.loadBranch(sessionId, session.leafEntryId()));
        if (dangling.isEmpty()) {
            return 0;
        }
        EntryId anchor = dangling.values().iterator().next().anchor();
        List<PendingCall> group = dangling.values().stream()
                .filter(call -> call.anchor().equals(anchor))
                .toList();
        boolean idle = session.status() == SessionStatus.IDLE;
        ChatSession current = idle ? session.markRunning() : session;
        Instant now = clock.instant();
        long seq = entries.countBySession(sessionId);
        EntryId parent = anchor;
        for (PendingCall pending : group) {
            ToolResultMessage message = new ToolResultMessage(pending.call().id(),
                    pending.call().name(), List.of(new TextBlock(INTERRUPTED_TEXT)), true,
                    Map.of("interrupted", true, "reason", "server-restart"), now);
            MessagePayload payload = new MessagePayload(message);
            SessionEntry entry = new SessionEntry(new EntryId(Ulid.parse(idGenerator.newId())),
                    sessionId, ++seq, parent, payload.entryType(),
                    SessionEntry.CURRENT_PAYLOAD_VERSION, payload,
                    TOKEN_COUNTER.countMessage(message), now);
            entries.append(entry);
            parent = entry.id();
        }
        current = current.advanceLeaf(parent);
        sessions.update(idle ? current.markIdle() : current);
        return group.size();
    }

    /**
     * 找出最早一段未闭合就出现其他消息的调用，或分支末尾仍未闭合的调用。
     *
     * <p>与上下文投影使用相同的顺序规则：工具结果之外的任何消息出现时，
     * 未闭合集合必须为空。第一次违反规则的集合必须整段补写，后面的 entry
     * 会随叶子回挂而离开当前分支。</p>
     */
    private Map<String, PendingCall> stranded(List<SessionEntry> branch) {
        Map<String, PendingCall> pending = new LinkedHashMap<>();
        for (SessionEntry entry : branch) {
            if (!(entry.payload() instanceof MessagePayload payload)) {
                continue;
            }
            AgentMessage message = payload.message();
            if (message instanceof ToolResultMessage result) {
                pending.remove(result.toolCallId());
                continue;
            }
            if (!pending.isEmpty()) {
                return pending;
            }
            if (message instanceof AssistantMessage assistant) {
                for (ContentBlock block : assistant.content()) {
                    if (block instanceof ToolCallBlock call) {
                        pending.put(call.id(), new PendingCall(call, entry.id()));
                    }
                }
            }
        }
        return pending;
    }

    private record PendingCall(ToolCallBlock call, EntryId anchor) {
    }
}
