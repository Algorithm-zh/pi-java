package com.piagent.trigger.http.session;

import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.CompactionSummaryMessage;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.CustomMessagePayload;
import com.piagent.domain.session.model.payload.CustomPayload;
import com.piagent.domain.session.model.payload.LabelPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.ModelChangePayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.SessionInfoPayload;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话 entry 的 HTTP 表示。
 *
 * <p>用一套稳定的 envelope 覆盖所有 entry 类型：{@code body.role/content} 给消息类
 * entry，{@code body.extra} 承载压缩、运行摘要、审批等结构化附加信息。这样前端
 * 不需要理解领域 payload 的 Java 类型。</p>
 *
 * @param id entry ID
 * @param parentId 父 entry；根为 null
 * @param seq 会话内序号
 * @param type entry 类型
 * @param createdAt 创建时间
 * @param body 展示内容
 */
public record SessionEntryResponse(String id, String parentId, long seq, String type,
        String createdAt, EntryBody body) {

    /**
     * 转换 entry。
     *
     * @param entry 会话 entry
     * @return 响应
     */
    public static SessionEntryResponse from(SessionEntry entry) {
        return new SessionEntryResponse(entry.id().toString(),
                entry.parentId() == null ? null : entry.parentId().toString(),
                entry.seq(), entry.type().name(), entry.createdAt().toString(), bodyOf(entry));
    }

    private static EntryBody bodyOf(SessionEntry entry) {
        return switch (entry.payload()) {
            case MessagePayload payload -> messageBody(payload.message());
            case CustomMessagePayload payload -> new EntryBody("user",
                    mapBlocks(payload.content()), null, null, null,
                    Map.of("customType", payload.customType(), "details", payload.details(),
                            "display", payload.display()));
            case RunSummaryPayload payload -> new EntryBody("system", List.of(), null, null, null,
                    summaryExtra(payload));
            case CompactionPayload payload -> new EntryBody("system", List.of(), null, null, null,
                    compactionExtra(payload));
            case BranchSummaryPayload payload -> new EntryBody("system", List.of(), null, null,
                    null, extra("summary", payload.summary(), "fromEntryId",
                            payload.fromEntryId().toString()));
            case ApprovalPayload payload -> new EntryBody("system", List.of(), payload.toolCallId(),
                    payload.toolName(), null, approvalExtra(payload));
            case ModelChangePayload payload -> new EntryBody("system", List.of(), null, null, null,
                    extra("modelProfileId", payload.modelProfileId(), "thinkingLevel",
                            payload.thinkingLevel().name()));
            case LabelPayload payload -> new EntryBody("system", List.of(), null, null, null,
                    extra("targetEntryId", payload.targetEntryId().toString(), "label",
                            payload.label() == null ? "" : payload.label()));
            case SessionInfoPayload payload -> new EntryBody("system", List.of(), null, null, null,
                    extra("name", payload.name(), "kind", "sessionInfo"));
            case CustomPayload payload -> new EntryBody("system", List.of(), null, null, null,
                    extra("customType", payload.customType(), "data", payload.data()));
        };
    }

    private static EntryBody messageBody(AgentMessage message) {
        return switch (message) {
            case UserMessage user -> new EntryBody("user", mapBlocks(user.content()), null, null,
                    null, Map.of());
            case AssistantMessage assistant -> new EntryBody("assistant",
                    mapBlocks(assistant.content()), null, null, null,
                    assistantExtra(assistant));
            case ToolResultMessage result -> new EntryBody("tool", mapBlocks(result.content()),
                    result.toolCallId(), result.toolName(), result.isError(),
                    Map.of("details", result.details()));
            case CompactionSummaryMessage summary -> new EntryBody("system", List.of(), null, null,
                    null, extra("summary", summary.summary(), "tokensBefore",
                            summary.tokensBefore()));
            case com.piagent.domain.session.model.message.BranchSummaryMessage summary ->
                    new EntryBody("system", List.of(), null, null, null,
                            extra("summary", summary.summary(), "fromEntryId",
                                    summary.fromEntryId().toString()));
        };
    }

    private static List<ContentBlockResponse> mapBlocks(List<ContentBlock> blocks) {
        return blocks.stream().map(ContentBlockResponse::from).toList();
    }

    private static Map<String, Object> assistantExtra(AssistantMessage message) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("provider", message.provider());
        extra.put("model", message.model());
        extra.put("stopReason", message.stopReason().name());
        extra.put("usage", usageMap(message.usage()));
        if (message.errorMessage() != null) {
            extra.put("errorMessage", message.errorMessage());
        }
        return Map.copyOf(extra);
    }

    private static Map<String, Object> summaryExtra(RunSummaryPayload payload) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("runId", payload.runId());
        extra.put("stopReason", payload.stopReason());
        extra.put("usage", usageMap(payload.usage()));
        extra.put("turns", payload.turns());
        extra.put("toolCalls", payload.toolCalls());
        extra.put("durationMs", payload.durationMs());
        if (payload.errorMessage() != null) {
            extra.put("errorMessage", payload.errorMessage());
        }
        return Map.copyOf(extra);
    }

    private static Map<String, Object> compactionExtra(CompactionPayload payload) {
        return extra("summary", payload.summary(), "tokensBefore", payload.tokensBefore(),
                "firstKeptEntryId", payload.firstKeptEntryId().toString(),
                "details", payload.details());
    }

    private static Map<String, Object> approvalExtra(ApprovalPayload payload) {
        return extra("approvalId", payload.approvalId(), "decision", payload.decision(),
                "decidedBy", payload.decidedBy(),
                "reason", payload.reason() == null ? "" : payload.reason());
    }

    private static Map<String, Object> usageMap(TokenUsage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("input", usage.input());
        map.put("output", usage.output());
        map.put("cacheRead", usage.cacheRead());
        map.put("cacheWrite", usage.cacheWrite());
        map.put("totalTokens", usage.totalTokens());
        return Map.copyOf(map);
    }

    private static Map<String, Object> extra(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            map.put(String.valueOf(pairs[index]), pairs[index + 1] == null ? "" : pairs[index + 1]);
        }
        return Map.copyOf(map);
    }

    /**
     * entry 展示体。
     *
     * @param role 消息角色：user / assistant / tool / system
     * @param content 内容块
     * @param toolCallId 工具调用 ID
     * @param toolName 工具名
     * @param isError 工具结果是否失败
     * @param extra 其他结构化信息
     */
    public record EntryBody(String role, List<ContentBlockResponse> content, String toolCallId,
            String toolName, Boolean isError, Map<String, Object> extra) {

        public EntryBody {
            content = content == null ? List.of() : List.copyOf(content);
            extra = extra == null ? Map.of() : Map.copyOf(extra);
        }
    }
}
