package com.piagent.domain.session;

import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.LabelPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.Ulid;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 会话领域测试夹具：固定时间、确定性 ULID 与常用 entry 构造。
 */
public final class SessionFixtures {

    public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private SessionFixtures() {
    }

    public static SessionId sessionId(int n) {
        return new SessionId(ulid(n));
    }

    public static EntryId entryId(int n) {
        return new EntryId(ulid(n));
    }

    public static Ulid ulid(int n) {
        return Ulid.parse("01JAVA0000" + String.format("%016d", n));
    }

    public static UserMessage user(String text) {
        return new UserMessage(List.of(new TextBlock(text)), NOW);
    }

    public static AssistantMessage assistant(String text) {
        return new AssistantMessage(List.of(new TextBlock(text)), "fake", "fake-model",
                TokenUsage.zero(), AssistantStopReason.STOP, null, NOW);
    }

    public static AssistantMessage assistantWithToolCall(String callId, String toolName) {
        List<ContentBlock> content = List.of(new TextBlock("calling " + toolName),
                new ToolCallBlock(callId, toolName, Map.of("path", "README.md")));
        return new AssistantMessage(content, "fake", "fake-model", TokenUsage.zero(),
                AssistantStopReason.TOOL_USE, null, NOW);
    }

    public static ToolResultMessage toolResult(String callId, String toolName) {
        return new ToolResultMessage(callId, toolName, List.of(new TextBlock("ok")), false, Map.of(), NOW);
    }

    public static SessionEntry entry(SessionId sessionId, long seq, EntryId parentId, EntryPayload payload, int tokens) {
        int idSeq = 100 + (int) seq;
        return new SessionEntry(entryId(idSeq), sessionId, seq, parentId, payload.entryType(),
                SessionEntry.CURRENT_PAYLOAD_VERSION, payload, tokens, NOW.plusMillis(seq));
    }

    public static SessionEntry message(SessionId sessionId, long seq, EntryId parentId, AgentMessage message) {
        return entry(sessionId, seq, parentId, new MessagePayload(message), 10);
    }

    public static SessionEntry compaction(SessionId sessionId, long seq, EntryId parentId, EntryId firstKept) {
        return entry(sessionId, seq, parentId,
                new CompactionPayload("summary", firstKept, 100, Map.of()), 30);
    }

    public static SessionEntry branchSummary(SessionId sessionId, long seq, EntryId parentId, EntryId from) {
        return entry(sessionId, seq, parentId, new BranchSummaryPayload(from, "branch", Map.of()), 12);
    }

    public static SessionEntry label(SessionId sessionId, long seq, EntryId parentId, EntryId target) {
        return entry(sessionId, seq, parentId, new LabelPayload(target, "checkpoint"), 0);
    }
}
