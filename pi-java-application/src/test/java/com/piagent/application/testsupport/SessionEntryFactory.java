package com.piagent.application.testsupport;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.Ulid;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 应用层测试的消息与 entry 工厂。
 *
 * <p>领域模块的 {@code SessionFixtures} 属于 domain 的测试类路径，不对外导出；
 * 应用层测试需要自己的确定性工厂，保证 ID、时间完全可复现。</p>
 */
public final class SessionEntryFactory {

    /** 固定时间。 */
    public static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    private SessionEntryFactory() {
    }

    /**
     * 生成确定性 ULID。
     *
     * @param n 序号
     * @return ULID
     */
    public static Ulid ulid(int n) {
        return Ulid.parse("01JAVA0000" + String.format("%016d", n));
    }

    /**
     * 生成确定性会话 ID。
     *
     * @param n 序号
     * @return 会话 ID
     */
    public static SessionId sessionId(int n) {
        return new SessionId(ulid(n));
    }

    /**
     * 生成确定性 entry ID。
     *
     * @param n 序号
     * @return entry ID
     */
    public static EntryId entryId(int n) {
        return new EntryId(ulid(n));
    }

    /**
     * 用户消息。
     *
     * @param text 文本
     * @return 消息
     */
    public static UserMessage user(String text) {
        return new UserMessage(List.of(new TextBlock(text)), NOW);
    }

    /**
     * 助手文本消息。
     *
     * @param text 文本
     * @return 消息
     */
    public static AssistantMessage assistant(String text) {
        return new AssistantMessage(List.of(new TextBlock(text)), "fake", "fake-model",
                TokenUsage.zero(), AssistantStopReason.STOP, null, NOW);
    }

    /**
     * 带工具调用的助手消息。
     *
     * @param callId 调用 ID
     * @param toolName 工具名
     * @return 消息
     */
    public static AssistantMessage assistantWithToolCall(String callId, String toolName) {
        return new AssistantMessage(List.of(new ToolCallBlock(callId, toolName,
                Map.of("path", "README.md"))), "fake", "fake-model", TokenUsage.zero(),
                AssistantStopReason.TOOL_USE, null, NOW);
    }

    /**
     * 工具结果消息。
     *
     * @param callId 调用 ID
     * @param toolName 工具名
     * @param content 内容
     * @return 消息
     */
    public static ToolResultMessage toolResult(String callId, String toolName, String content) {
        return new ToolResultMessage(callId, toolName, List.of(new TextBlock(content)), false,
                Map.of(), NOW);
    }

    /**
     * 构造一条 entry。
     *
     * @param sessionId 会话 ID
     * @param seq 序号
     * @param parentId 父 entry
     * @param payload 载荷
     * @param tokens token 估算
     * @return entry
     */
    public static SessionEntry entry(SessionId sessionId, long seq, EntryId parentId,
            EntryPayload payload, int tokens) {
        return new SessionEntry(entryId(100 + (int) seq), sessionId, seq, parentId,
                payload.entryType(), SessionEntry.CURRENT_PAYLOAD_VERSION, payload, tokens,
                NOW.plusMillis(seq));
    }

    /**
     * 构造一条消息 entry。
     *
     * @param sessionId 会话 ID
     * @param seq 序号
     * @param parentId 父 entry
     * @param message 消息
     * @param tokens token 估算
     * @return entry
     */
    public static SessionEntry message(SessionId sessionId, long seq, EntryId parentId,
            AgentMessage message, int tokens) {
        return entry(sessionId, seq, parentId, new MessagePayload(message), tokens);
    }

    /**
     * 构造一条压缩 entry。
     *
     * @param sessionId 会话 ID
     * @param seq 序号
     * @param parentId 父 entry
     * @param firstKeptEntryId 保留起点
     * @return entry
     */
    public static SessionEntry compaction(SessionId sessionId, long seq, EntryId parentId,
            EntryId firstKeptEntryId) {
        return entry(sessionId, seq, parentId,
                new CompactionPayload("summary", firstKeptEntryId, 100, Map.of()), 30);
    }
}
