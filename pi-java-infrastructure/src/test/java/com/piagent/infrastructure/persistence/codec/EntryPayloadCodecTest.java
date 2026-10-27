package com.piagent.infrastructure.persistence.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.AssistantStopReason;
import com.piagent.domain.session.model.message.ImageBlock;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.session.model.message.UserMessage;
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
import com.piagent.domain.shared.Ulid;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * entry 载荷 JSON 编解码单元测试。
 *
 * <p>目标：每种 payload 往返后语义完全相等；未知版本与脏 JSON 必须显式失败。
 */
class EntryPayloadCodecTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final EntryPayloadCodec codec = new EntryPayloadCodec(new ObjectMapper());

    @Test
    void roundTripsAllPayloadTypes() {
        for (EntryPayload payload : samples()) {
            String json = codec.encode(payload);
            EntryPayload decoded = codec.decode(payload.entryType(),
                    SessionEntry.CURRENT_PAYLOAD_VERSION, json);

            assertThat(decoded).as("payload type %s", payload.entryType()).isEqualTo(payload);
        }
    }

    @Test
    void rejectsFuturePayloadVersion() {
        EntryPayload payload = new SessionInfoPayload("v2");

        assertThatThrownBy(() -> codec.decode(payload.entryType(),
                SessionEntry.CURRENT_PAYLOAD_VERSION + 1, codec.encode(payload)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.UNSUPPORTED_PAYLOAD));
    }

    @Test
    void rejectsMalformedJson() {
        assertThatThrownBy(() -> codec.decode(EntryType.MESSAGE, 1, "{not-json"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ENTRY));
    }

    @Test
    void rejectsMissingRequiredField() {
        assertThatThrownBy(() -> codec.decode(EntryType.SESSION_INFO, 1, "{}"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ENTRY));
    }

    private List<EntryPayload> samples() {
        TokenUsage usage = new TokenUsage(10, 20, 5, 0, 30);
        return List.of(
                new MessagePayload(new UserMessage(List.of(new TextBlock("你好")), NOW)),
                new MessagePayload(new UserMessage(List.of(
                        new TextBlock("看看这张图"),
                        new ImageBlock("att-1", "image/png", "shot.png")), NOW)),
                new MessagePayload(new AssistantMessage(
                        List.of(new TextBlock("调用"), new ToolCallBlock("call-1", "read",
                                Map.of("path", "README.md"))),
                        "fake", "fake-model", usage, AssistantStopReason.TOOL_USE, null, NOW)),
                new MessagePayload(new ToolResultMessage("call-1", "read",
                        List.of(new TextBlock("内容")), false, Map.of("lines", 3), NOW)),
                new CustomMessagePayload("demo", List.of(new TextBlock("自定义")), true, Map.of("k", "v")),
                new CompactionPayload("压缩摘要", eid(1), 1200, Map.of("tokensBefore", 1200)),
                new BranchSummaryPayload(eid(2), "分支摘要", Map.of("source", "s-1")),
                new ModelChangePayload("deepseek-chat", ThinkingLevel.HIGH),
                new CustomPayload("note", Map.of("x", 1)),
                new LabelPayload(eid(3), null),
                new SessionInfoPayload("会话名"),
                new ApprovalPayload("appr-1", "call-1", "read", "APPROVED", null, "USER"),
                new RunSummaryPayload("run-1", "STOP", usage, 2, 3, 1200, null),
                new GitCheckpointPayload("run-1", eid(4), "tree-base", "head-sha", "main"),
                new GitCheckpointPayload("run-2", null, "tree-empty", null, null),
                new GitChangeSetPayload("run-1", 2, eid(5), "tree-base", "tree-after",
                        List.of(new FileChange("src/App.java", GitChangeKind.MODIFIED, 12, 3, false),
                                new FileChange("logo.bin", GitChangeKind.ADDED, 0, 0, true)),
                        "diff --git a/src/App.java b/src/App.java\n+hello\n", false,
                        "feat: 更新 App"),
                new PlanPayload("run-1", List.of(
                        new PlanItem("读代码", PlanItemStatus.DONE),
                        new PlanItem("改实现", PlanItemStatus.IN_PROGRESS),
                        new PlanItem("跑测试", PlanItemStatus.PENDING))),
                new PlanPayload("", List.of()),
                new VerificationPayload("run-1", "mvn -q test", false, 1, false, true,
                        3200, "Tests run: 3, Failures: 1"));
    }

    private static EntryId eid(int n) {
        return new EntryId(Ulid.parse("01ENTRY00" + String.format("%017d", n)));
    }
}
