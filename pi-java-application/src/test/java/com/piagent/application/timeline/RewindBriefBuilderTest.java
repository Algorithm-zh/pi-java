package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class RewindBriefBuilderTest {

    private static final SessionId SESSION_ID = SessionEntryFactory.sessionId(1);
    private static final Instant AT = Instant.parse("2026-11-05T14:32:00Z");
    private static final Clock CLOCK = Clock.fixed(AT, ZoneOffset.UTC);
    private final RewindBriefBuilder builder = new RewindBriefBuilder(CLOCK);

    @Test
    void rendersCountsAndAvoidList() {
        SessionEntry checkpoint = entry(2, SessionEntryFactory.entryId(101),
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);
        SessionEntry user = SessionEntryFactory.message(SESSION_ID, 1, null,
                new UserMessage(List.of(new com.piagent.domain.session.model.message.TextBlock(
                        "修复登录")), Instant.parse("2026-11-05T14:30:00Z")), 8);
        SessionEntry denied = entry(3, SessionEntryFactory.entryId(102),
                new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user"), 2);
        SessionEntry failed = entry(4, SessionEntryFactory.entryId(103),
                new VerificationPayload("run-1", "mvn test", false, 1, false, false, 120,
                        "fail"), 2);
        SessionEntry plan = entry(5, SessionEntryFactory.entryId(104),
                new PlanPayload("run-1", List.of(
                        new PlanItem("改登录", PlanItemStatus.DONE),
                        new PlanItem("补登录测试", PlanItemStatus.PENDING))), 2);

        RewindBrief brief = builder.build(checkpoint,
                List.of(user, checkpoint, denied, failed, plan), List.of("a.txt", "b.txt"),
                "换一种修法");

        assertThat(brief.summary())
                .contains("从 14:32 的决策点回退")
                .contains("丢弃 1 轮对话")
                .contains("1 次被拒绝的审批")
                .contains("1 次失败的验证")
                .contains("已还原 2 个文件（a.txt、b.txt）")
                .contains("未完成：补登录测试")
                .contains("write（已拒绝）")
                .contains("mvn test（已验证失败）")
                .contains("备注：换一种修法");
        assertThat(brief.summary().length()).isLessThanOrEqualTo(400);
        assertThat(brief.details())
                .containsEntry("kind", "rewind")
                .containsEntry("discardedEntries", 5)
                .containsEntry("discardedRuns", 1)
                .containsEntry("restoredFiles", 2)
                .containsEntry("restoredPaths", List.of("a.txt", "b.txt"))
                .containsEntry("deniedApprovals", 1)
                .containsEntry("failedVerifications", 1)
                .containsEntry("unfinishedPlanItems", 1);
    }

    @Test
    void rejectsTooLongNote() {
        SessionEntry checkpoint = entry(1, null,
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);

        assertThatThrownBy(() -> builder.build(checkpoint, List.of(), List.of(), "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void truncatesOversizedSummary() {
        SessionEntry checkpoint = entry(1, null,
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);
        SessionEntry plan = entry(2, SessionEntryFactory.entryId(101), new PlanPayload("run-1",
                List.of(new PlanItem("x".repeat(200), PlanItemStatus.PENDING),
                        new PlanItem("x".repeat(200), PlanItemStatus.PENDING),
                        new PlanItem("x".repeat(200), PlanItemStatus.PENDING))), 2);

        RewindBrief brief = builder.build(checkpoint, List.of(checkpoint, plan), List.of("a.txt"),
                "y".repeat(200));

        assertThat(brief.summary()).hasSize(400).endsWith("…");
    }

    @Test
    void ignoresNullOrBlankNote() {
        SessionEntry checkpoint = entry(1, null,
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);

        assertThat(builder.build(checkpoint, List.of(), List.of(), null).summary())
                .doesNotContain("备注：");
        assertThat(builder.build(checkpoint, List.of(), List.of(), "   ").summary())
                .doesNotContain("备注：");
    }

    @Test
    void rendersZeroCountsWhenNothingIsDiscarded() {
        SessionEntry checkpoint = entry(1, null,
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);

        RewindBrief brief = builder.build(checkpoint, List.of(), List.of(), null);

        assertThat(brief.summary())
                .contains("丢弃 0 轮对话")
                .contains("已还原 0 个文件")
                .doesNotContain("继续时不要重复")
                .doesNotContain("备注：");
        assertThat(brief.details())
                .containsEntry("discardedEntries", 0)
                .containsEntry("discardedRuns", 0)
                .containsEntry("deniedApprovals", 0)
                .containsEntry("failedVerifications", 0)
                .containsEntry("unfinishedPlanItems", 0);
    }

    @Test
    void omitsUnfinishedSectionWhenAllPlanItemsAreDone() {
        SessionEntry checkpoint = entry(1, null,
                new GitCheckpointPayload("run-1", null, "tree-0", "head", "main"), 0);
        SessionEntry plan = entry(2, SessionEntryFactory.entryId(101), new PlanPayload("run-1",
                List.of(new PlanItem("改登录", PlanItemStatus.DONE),
                        new PlanItem("补登录测试", PlanItemStatus.DONE))), 2);

        RewindBrief brief = builder.build(checkpoint, List.of(checkpoint, plan), List.of(), null);

        assertThat(brief.summary()).doesNotContain("未完成");
        assertThat(brief.details()).containsEntry("unfinishedPlanItems", 0);
    }

    /**
     * 构造带固定创建时间的 entry：工厂把时间钉在 2026-10-22，无法覆盖断言里的决策点时间；
     * 非根 entry 还必须带父节点，这里按 seq 顺序给出。
     */
    private static SessionEntry entry(long seq, EntryId parent, EntryPayload payload, int tokens) {
        return new SessionEntry(SessionEntryFactory.entryId(100 + (int) seq), SESSION_ID, seq,
                parent, payload.entryType(), SessionEntry.CURRENT_PAYLOAD_VERSION, payload, tokens,
                AT);
    }
}
