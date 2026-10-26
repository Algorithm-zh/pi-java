package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.List;

/**
 * 计划与验证载荷的 JSON 编解码。
 *
 * <p>从 {@link EntryPayloadCodec} 拆出，保持主编解码入口在文件长度上限内；
 * 语义仍由 {@link com.piagent.domain.session.model.EntryType} 决定。</p>
 */
final class PlanEntryCodec {

    private PlanEntryCodec() {
    }

    static void encodePlan(ObjectNode node, PlanPayload value) {
        node.put("runId", value.runId());
        ArrayNode items = node.putArray("items");
        for (PlanItem item : value.items()) {
            items.addObject().put("content", item.content())
                    .put("status", item.status().wireName());
        }
    }

    static void encodeVerification(ObjectNode node, VerificationPayload value) {
        node.put("runId", value.runId()).put("command", value.command())
                .put("passed", value.passed());
        if (value.exitCode() == null) {
            node.putNull("exitCode");
        } else {
            node.put("exitCode", value.exitCode());
        }
        node.put("timedOut", value.timedOut()).put("truncated", value.truncated())
                .put("durationMs", value.durationMs()).put("output", value.output());
    }

    static PlanPayload decodePlan(JsonNode root) {
        return new PlanPayload(root.path("runId").asText(""),
                decodeItems(root.get("items")));
    }

    static VerificationPayload decodeVerification(JsonNode root) {
        return new VerificationPayload(root.path("runId").asText(""),
                root.path("command").asText(""), root.path("passed").asBoolean(false),
                nullableInt(root, "exitCode"), root.path("timedOut").asBoolean(false),
                root.path("truncated").asBoolean(false), root.path("durationMs").asLong(0),
                root.path("output").asText(""));
    }

    private static List<PlanItem> decodeItems(JsonNode node) {
        List<PlanItem> items = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return items;
        }
        for (JsonNode item : node) {
            items.add(new PlanItem(requiredText(item, "content"),
                    PlanItemStatus.fromWire(requiredText(item, "status"))));
        }
        return items;
    }

    private static Integer nullableInt(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new DomainException(DomainErrorCode.INVALID_ENTRY,
                    "缺少字符串字段: " + field);
        }
        return value.asText();
    }
}
