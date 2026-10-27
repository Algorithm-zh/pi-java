package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.CompactionPayload;
import com.piagent.domain.session.model.payload.CustomMessagePayload;
import com.piagent.domain.session.model.payload.CustomPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.LabelPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.ModelChangePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.SessionInfoPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.List;

/**
 * entry 载荷 JSON 编解码入口。
 *
 * <p>顶层结构由 {@code payload_version} 解释；当前版本为 2（新增图片内容块），
 * 解码仍接受旧版本，后续只允许新增字段。
 * 解码时按 {@link EntryType} 选择分支，未知类型明确失败，不猜测语义。
 */
public final class EntryPayloadCodec {

    private final ObjectMapper mapper;
    private final MessageCodec messages;

    /**
     * 创建编解码器。
     *
     * @param mapper Jackson 映射器
     */
    public EntryPayloadCodec(ObjectMapper mapper) {
        this.mapper = mapper;
        this.messages = new MessageCodec(mapper);
    }

    /**
     * 编码载荷。
     *
     * @param payload 载荷
     * @return JSON 字符串
     */
    public String encode(EntryPayload payload) {
        ObjectNode node = mapper.createObjectNode();
        switch (payload) {
            case MessagePayload value -> node.setAll((ObjectNode) messages.encode(value.message()));
            case CustomMessagePayload value -> encodeCustomMessage(node, value);
            case CompactionPayload value -> encodeCompaction(node, value);
            case BranchSummaryPayload value -> encodeBranchSummary(node, value);
            case ModelChangePayload value -> node.put("modelProfileId", value.modelProfileId())
                    .put("thinkingLevel", value.thinkingLevel().name());
            case CustomPayload value -> {
                node.put("customType", value.customType());
                node.set("data", mapper.valueToTree(value.data()));
            }
            case LabelPayload value -> encodeLabel(node, value);
            case SessionInfoPayload value -> node.put("name", value.name());
            case ApprovalPayload value -> encodeApproval(node, value);
            case RunSummaryPayload value -> encodeRunSummary(node, value);
            case GitCheckpointPayload value -> encodeCheckpoint(node, value);
            case GitChangeSetPayload value -> encodeChanges(node, value);
            case PlanPayload value -> PlanEntryCodec.encodePlan(node, value);
            case VerificationPayload value -> PlanEntryCodec.encodeVerification(node, value);
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("entry payload 序列化失败", error);
        }
    }

    private void encodeCustomMessage(ObjectNode node, CustomMessagePayload value) {
        node.put("customType", value.customType());
        node.set("content", messages.encodeBlocks(value.content()));
        node.put("display", value.display());
        node.set("details", mapper.valueToTree(value.details()));
    }

    private void encodeCompaction(ObjectNode node, CompactionPayload value) {
        node.put("summary", value.summary());
        node.put("firstKeptEntryId", value.firstKeptEntryId().toString());
        node.put("tokensBefore", value.tokensBefore());
        node.set("details", mapper.valueToTree(value.details()));
    }

    private void encodeBranchSummary(ObjectNode node, BranchSummaryPayload value) {
        node.put("fromEntryId", value.fromEntryId().toString());
        node.put("summary", value.summary());
        node.set("details", mapper.valueToTree(value.details()));
    }

    private void encodeLabel(ObjectNode node, LabelPayload value) {
        node.put("targetEntryId", value.targetEntryId().toString());
        if (value.label() == null) {
            node.putNull("label");
        } else {
            node.put("label", value.label());
        }
    }

    private void encodeApproval(ObjectNode node, ApprovalPayload value) {
        node.put("approvalId", value.approvalId()).put("toolCallId", value.toolCallId())
                .put("toolName", value.toolName()).put("decision", value.decision());
        putNullable(node, "reason", value.reason());
        node.put("decidedBy", value.decidedBy());
    }

    private void encodeCheckpoint(ObjectNode node, GitCheckpointPayload value) {
        node.put("runId", value.runId());
        putNullable(node, "baseLeafId",
                value.baseLeafId() == null ? null : value.baseLeafId().toString());
        node.put("baseTreeSha", value.baseTreeSha());
        putNullable(node, "headSha", value.headSha());
        putNullable(node, "branch", value.branch());
    }

    private void encodeChanges(ObjectNode node, GitChangeSetPayload value) {
        node.put("runId", value.runId()).put("turn", value.turn());
        putNullable(node, "baseLeafId",
                value.baseLeafId() == null ? null : value.baseLeafId().toString());
        node.put("baseTreeSha", value.baseTreeSha()).put("treeSha", value.treeSha());
        node.put("diff", value.diff()).put("truncated", value.truncated())
                .put("suggestedCommit", value.suggestedCommit());
        ArrayNode files = node.putArray("files");
        for (FileChange change : value.files()) {
            files.addObject().put("path", change.path()).put("kind", change.kind().name())
                    .put("additions", change.additions()).put("deletions", change.deletions())
                    .put("binary", change.binary());
        }
    }

    private void encodeRunSummary(ObjectNode node, RunSummaryPayload value) {
        node.put("runId", value.runId()).put("stopReason", value.stopReason());
        node.set("usage", messages.encodeUsage(value.usage()));
        node.put("turns", value.turns()).put("toolCalls", value.toolCalls())
                .put("durationMs", value.durationMs());
        putNullable(node, "errorMessage", value.errorMessage());
    }

    /**
     * 解码载荷。
     *
     * @param type entry 类型
     * @param payloadVersion 载荷版本
     * @param json JSON 字符串
     * @return 载荷
     */
    public EntryPayload decode(EntryType type, int payloadVersion, String json) {
        if (payloadVersion > SessionEntry.CURRENT_PAYLOAD_VERSION) {
            throw new DomainException(DomainErrorCode.UNSUPPORTED_PAYLOAD,
                    "不支持的 payload 版本: " + payloadVersion);
        }
        try {
            JsonNode root = mapper.readTree(json);
            return switch (type) {
                case MESSAGE -> new MessagePayload(messages.decode(root));
                case CUSTOM_MESSAGE -> new CustomMessagePayload(requiredText(root, "customType"),
                        messages.decodeBlocks(root.get("content")), root.path("display").asBoolean(true),
                        messages.decodeMap(root.get("details")));
                case COMPACTION -> new CompactionPayload(requiredText(root, "summary"),
                        EntryId.parse(requiredText(root, "firstKeptEntryId")),
                        root.path("tokensBefore").asInt(), messages.decodeMap(root.get("details")));
                case BRANCH_SUMMARY -> new BranchSummaryPayload(
                        EntryId.parse(requiredText(root, "fromEntryId")),
                        requiredText(root, "summary"), messages.decodeMap(root.get("details")));
                case MODEL_CHANGE -> new ModelChangePayload(requiredText(root, "modelProfileId"),
                        ThinkingLevel.valueOf(requiredText(root, "thinkingLevel")));
                case CUSTOM -> new CustomPayload(requiredText(root, "customType"),
                        messages.decodeMap(root.get("data")));
                case LABEL -> new LabelPayload(EntryId.parse(requiredText(root, "targetEntryId")),
                        nullableText(root, "label"));
                case SESSION_INFO -> new SessionInfoPayload(requiredText(root, "name"));
                case APPROVAL -> new ApprovalPayload(requiredText(root, "approvalId"),
                        requiredText(root, "toolCallId"), requiredText(root, "toolName"),
                        requiredText(root, "decision"), nullableText(root, "reason"),
                        requiredText(root, "decidedBy"));
                case RUN_SUMMARY -> decodeRunSummary(root);
                case GIT_CHECKPOINT -> new GitCheckpointPayload(requiredText(root, "runId"),
                        nullableEntryId(root, "baseLeafId"), requiredText(root, "baseTreeSha"),
                        nullableText(root, "headSha"), nullableText(root, "branch"));
                case GIT_CHANGES -> new GitChangeSetPayload(requiredText(root, "runId"),
                        root.path("turn").asInt(), nullableEntryId(root, "baseLeafId"),
                        requiredText(root, "baseTreeSha"), requiredText(root, "treeSha"),
                        decodeFiles(root.get("files")), root.path("diff").asText(""),
                        root.path("truncated").asBoolean(false),
                        root.path("suggestedCommit").asText(""));
                case PLAN -> PlanEntryCodec.decodePlan(root);
                case VERIFICATION -> PlanEntryCodec.decodeVerification(root);
            };
        } catch (DomainException error) {
            throw error;
        } catch (Exception error) {
            throw new DomainException(DomainErrorCode.INVALID_ENTRY,
                    "entry payload JSON 解析失败: " + type, error);
        }
    }

    private RunSummaryPayload decodeRunSummary(JsonNode root) {
        TokenUsage usage = messages.decodeUsage(root.get("usage"));
        return new RunSummaryPayload(requiredText(root, "runId"), requiredText(root, "stopReason"),
                usage, root.path("turns").asInt(), root.path("toolCalls").asInt(),
                root.path("durationMs").asLong(), nullableText(root, "errorMessage"));
    }

    private List<FileChange> decodeFiles(JsonNode node) {
        List<FileChange> files = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return files;
        }
        for (JsonNode item : node) {
            files.add(new FileChange(requiredText(item, "path"),
                    GitChangeKind.valueOf(requiredText(item, "kind")),
                    item.path("additions").asInt(), item.path("deletions").asInt(),
                    item.path("binary").asBoolean(false)));
        }
        return files;
    }

    private EntryId nullableEntryId(JsonNode node, String field) {
        String value = nullableText(node, field);
        return value == null || value.isBlank() ? null : EntryId.parse(value);
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new DomainException(DomainErrorCode.INVALID_ENTRY, "缺少字符串字段: " + field);
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
}
