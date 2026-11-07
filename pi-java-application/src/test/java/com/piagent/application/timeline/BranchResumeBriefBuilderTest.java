package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchResumeBriefBuilderTest {

    private static final SessionId SESSION = SessionEntryFactory.sessionId(2);
    private final BranchResumeBriefBuilder builder = new BranchResumeBriefBuilder();

    @Test
    void rendersBothSidesIntentAndAvoidList() {
        List<SessionEntry> left = List.of(
                entry(1, new MessagePayload(SessionEntryFactory.user("旧思路"))),
                entry(2, new GitCheckpointPayload("run-l", null, "tree-l", "head", "main")));
        List<SessionEntry> target = List.of(
                entry(3, new MessagePayload(SessionEntryFactory.user("第一版"))),
                entry(4, new MessagePayload(SessionEntryFactory.user("修复登录超时"))),
                entry(5, new GitCheckpointPayload("run-t", null, "tree-t", "head", "main")),
                entry(6, new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user")),
                entry(7, new VerificationPayload("run-t", "mvn test", false, 1, false, false,
                        90, "fail")));

        BranchResumeBrief brief = builder.build(SessionEntryFactory.entryId(50), left, target,
                List.of("a.java", "b.java"), "tree-live", "tree-t", "先跑测试");

        assertThat(brief.summary())
                .contains("离开 1 轮对话、1 个 run")
                .contains("目标分支有 2 轮对话、1 个 run")
                .contains("已改动 2 个文件（a.java、b.java）")
                .contains("目标分支最后一次意图：修复登录超时")
                .contains("write（已拒绝）")
                .contains("mvn test（已验证失败）")
                .contains("备注：先跑测试");
        assertThat(brief.details())
                .containsEntry("kind", "resume")
                .containsEntry("branchEntryId", SessionEntryFactory.entryId(50).toString())
                .containsEntry("leftEntries", 2)
                .containsEntry("leftRuns", 1)
                .containsEntry("targetEntries", 5)
                .containsEntry("targetRuns", 1)
                .containsEntry("restoredFiles", 2)
                .containsEntry("fromTreeSha", "tree-live")
                .containsEntry("toTreeSha", "tree-t");
        assertThat(brief.details().get("restoredPaths"))
                .isEqualTo(List.of("a.java", "b.java"));
    }

    @Test
    void truncatesSummaryAtFourHundredCharacters() {
        List<SessionEntry> target = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            target.add(entry(100 + index, new MessagePayload(
                    SessionEntryFactory.user("很长的目标意图".repeat(5)))));
        }

        BranchResumeBrief brief = builder.build(SessionEntryFactory.entryId(51), List.of(),
                target, List.of("a".repeat(40), "b".repeat(40), "c".repeat(40), "d"),
                "from", "to", "y".repeat(200));

        assertThat(brief.summary()).hasSize(400).endsWith("…");
    }

    @Test
    void rejectsTooLongNote() {
        assertThatThrownBy(() -> builder.build(SessionEntryFactory.entryId(52), List.of(),
                List.of(), List.of(), "from", "to", "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    private SessionEntry entry(long seq, EntryPayload payload) {
        return SessionEntryFactory.entry(SESSION, seq, null, payload, 0);
    }
}
