package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.BranchSummaryMessage;
import com.piagent.domain.session.model.message.CompactionSummaryMessage;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话消息 JSON 编解码。
 *
 * <p>格式见 docs/03-database-design.md 第 5 节；角色字段决定反序列化分支。
 * 时间统一使用 ISO-8601，避免依赖数据库时区。
 */
final class MessageCodec {

    private final ObjectMapper mapper;
    private final ContentBlockCodec blocks;

    MessageCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        this.blocks = new ContentBlockCodec(mapper);
    }

    JsonNode encode(AgentMessage message) {
        ObjectNode node = mapper.createObjectNode();
        switch (message) {
            case UserMessage user -> {
                node.put("role", "user");
                node.set("content", encodeBlocks(user.content()));
                node.put("timestamp", user.timestamp().toString());
            }
            case AssistantMessage assistant -> encodeAssistant(node, assistant);
            case ToolResultMessage result -> {
                node.put("role", "toolResult").put("toolCallId", result.toolCallId())
                        .put("toolName", result.toolName());
                node.set("content", encodeBlocks(result.content()));
                node.put("isError", result.isError());
                node.set("details", mapper.valueToTree(result.details()));
                node.put("timestamp", result.timestamp().toString());
            }
            case CompactionSummaryMessage summary -> {
                node.put("role", "compactionSummary").put("summary", summary.summary());
                node.put("tokensBefore", summary.tokensBefore());
                node.put("timestamp", summary.timestamp().toString());
            }
            case BranchSummaryMessage summary -> {
                node.put("role", "branchSummary").put("summary", summary.summary());
                node.put("fromEntryId", summary.fromEntryId().toString());
                node.put("timestamp", summary.timestamp().toString());
            }
        }
        return node;
    }

    AgentMessage decode(JsonNode node) {
        String role = node.path("role").asText("");
        return switch (role) {
            case "user" -> new UserMessage(decodeBlocks(node.get("content")), timestamp(node));
            case "assistant" -> decodeAssistant(node);
            case "toolResult" -> new ToolResultMessage(requiredText(node, "toolCallId"),
                    requiredText(node, "toolName"), decodeBlocks(node.get("content")),
                    node.path("isError").asBoolean(false), decodeMap(node.get("details")), timestamp(node));
            case "compactionSummary" -> new CompactionSummaryMessage(requiredText(node, "summary"),
                    node.path("tokensBefore").asInt(), timestamp(node));
            case "branchSummary" -> new BranchSummaryMessage(requiredText(node, "summary"),
                    EntryId.parse(requiredText(node, "fromEntryId")), timestamp(node));
            default -> throw invalid("未知的消息角色: " + role);
        };
    }

    ArrayNode encodeBlocks(List<ContentBlock> content) {
        ArrayNode array = mapper.createArrayNode();
        for (ContentBlock block : content) {
            array.add(blocks.encode(block));
        }
        return array;
    }

    List<ContentBlock> decodeBlocks(JsonNode node) {
        if (node == null || !node.isArray()) {
            throw invalid("content 必须是数组");
        }
        List<ContentBlock> content = new ArrayList<>();
        for (JsonNode item : node) {
            content.add(blocks.decode(item));
        }
        return content;
    }

    Map<String, Object> decodeMap(JsonNode node) {
        if (node == null || node.isNull()) {
            return Map.of();
        }
        return mapper.convertValue(node, mapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    private void encodeAssistant(ObjectNode node, AssistantMessage assistant) {
        node.put("role", "assistant");
        node.set("content", encodeBlocks(assistant.content()));
        ObjectNode model = node.putObject("model");
        model.put("provider", assistant.provider()).put("modelName", assistant.model());
        node.set("usage", encodeUsage(assistant.usage()));
        node.put("stopReason", assistant.stopReason().name());
        if (assistant.errorMessage() == null) {
            node.putNull("errorMessage");
        } else {
            node.put("errorMessage", assistant.errorMessage());
        }
        node.put("timestamp", assistant.timestamp().toString());
    }

    private AssistantMessage decodeAssistant(JsonNode node) {
        JsonNode model = node.path("model");
        return new AssistantMessage(decodeBlocks(node.get("content")),
                requiredText(model, "provider"), requiredText(model, "modelName"),
                decodeUsage(node.get("usage")),
                AssistantStopReason.valueOf(requiredText(node, "stopReason")),
                nullableText(node, "errorMessage"), timestamp(node));
    }

    ObjectNode encodeUsage(TokenUsage usage) {
        ObjectNode node = mapper.createObjectNode();
        node.put("input", usage.input()).put("output", usage.output())
                .put("cacheRead", usage.cacheRead()).put("cacheWrite", usage.cacheWrite())
                .put("totalTokens", usage.totalTokens());
        return node;
    }

    TokenUsage decodeUsage(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("assistant 消息缺少 usage");
        }
        return new TokenUsage(node.path("input").asLong(), node.path("output").asLong(),
                node.path("cacheRead").asLong(), node.path("cacheWrite").asLong(),
                node.path("totalTokens").asLong());
    }

    private Instant timestamp(JsonNode node) {
        return Instant.parse(requiredText(node, "timestamp"));
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("缺少字符串字段: " + field);
        }
        return value.asText();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private DomainException invalid(String message) {
        return new DomainException(DomainErrorCode.INVALID_ENTRY, message);
    }
}
