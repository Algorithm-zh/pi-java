package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ThinkingBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 内容块 JSON 编解码。
 *
 * <p>只使用 Jackson 树模型，不把注解带进领域类型；未知类型明确失败，避免静默丢内容。
 */
final class ContentBlockCodec {

    private final ObjectMapper mapper;

    ContentBlockCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    JsonNode encode(ContentBlock block) {
        ObjectNode node = mapper.createObjectNode();
        switch (block) {
            case TextBlock text -> node.put("type", "text").put("text", text.text());
            case ThinkingBlock thinking -> {
                node.put("type", "thinking").put("thinking", thinking.thinking());
                putNullable(node, "signature", thinking.signature());
            }
            case ToolCallBlock call -> {
                node.put("type", "toolCall").put("id", call.id()).put("name", call.name());
                node.set("arguments", mapper.valueToTree(call.arguments()));
            }
        }
        return node;
    }

    ContentBlock decode(JsonNode node) {
        String type = node.path("type").asText("");
        return switch (type) {
            case "text" -> new TextBlock(requiredText(node, "text"));
            case "thinking" -> new ThinkingBlock(requiredText(node, "thinking"), nullableText(node, "signature"));
            case "toolCall" -> new ToolCallBlock(requiredText(node, "id"), requiredText(node, "name"),
                    decodeArguments(node.get("arguments")));
            default -> throw invalid("未知的内容块类型: " + type);
        };
    }

    private Map<String, Object> decodeArguments(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw invalid("工具调用缺少 arguments 对象");
        }
        return mapper.convertValue(node, mapper.getTypeFactory()
                .constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw invalid("内容块缺少字符串字段: " + field);
        }
        return value.asText();
    }

    private String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private DomainException invalid(String message) {
        return new DomainException(DomainErrorCode.INVALID_ENTRY, message);
    }
}
