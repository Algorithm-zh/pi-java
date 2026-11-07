package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BranchComparisonProjectorTest {

    private static final SessionId SESSION = SessionEntryFactory.sessionId(1);
    private static final EntryId LEAF = SessionEntryFactory.entryId(999);

    private final BranchComparisonProjector projector = new BranchComparisonProjector();

    @Test
    void projectsIntentRunsApprovalsVerificationsPlanAndFiles() {
        List<SessionEntry> segment = List.of(
                entry(1, new MessagePayload(SessionEntryFactory.user("修复登录"))),
                entry(2, new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main")),
                entry(3, new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user")),
                entry(4, new VerificationPayload("run-1", "mvn test", false, 1, false, false, 120, "fail")),
                entry(5, new PlanPayload("run-1", List.of(
                        new PlanItem("改登录", PlanItemStatus.DONE),
                        new PlanItem("补测试", PlanItemStatus.PENDING)))),
                entry(6, changeSet("run-1", 1, file("A.java", 10, 2), file("B.java", 3, 1))),
                entry(7, new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 2, 3, 900, null)),
                entry(8, new MessagePayload(SessionEntryFactory.user("再改一次"))),
                entry(9, changeSet("run-2", 1, file("A.java", 5, 1), file("C.java", 1, 0))),
                entry(10, new RunSummaryPayload("run-2", "COMPLETED", TokenUsage.zero(), 1, 0, 300, null)));

        SessionBranchComparison.BranchSide side = projector.project(segment, LEAF);

        assertThat(side.leafEntryId()).isEqualTo(LEAF);
        assertThat(side.intents()).containsExactly("修复登录", "再改一次");
        assertThat(side.runs()).isEqualTo(1);
        assertThat(side.completedRuns()).isEqualTo(2);
        assertThat(side.turns()).isEqualTo(3);
        assertThat(side.toolCalls()).isEqualTo(3);
        assertThat(side.stopReasons()).containsExactly("COMPLETED");
        assertThat(side.approvals().total()).isEqualTo(1);
        assertThat(side.approvals().denied()).isEqualTo(1);
        assertThat(side.approvals().deniedTools()).containsExactly("write");
        assertThat(side.verifications().total()).isEqualTo(1);
        assertThat(side.verifications().failed()).isEqualTo(1);
        assertThat(side.verifications().failedCommands()).containsExactly("mvn test");
        assertThat(side.plan().total()).isEqualTo(2);
        assertThat(side.plan().done()).isEqualTo(1);
        assertThat(side.plan().unfinished()).containsExactly("补测试");
        assertThat(side.changes().turns()).isEqualTo(2);
        assertThat(side.changes().files()).isEqualTo(3);
        assertThat(side.changes().additions()).isEqualTo(19);
        assertThat(side.changes().deletions()).isEqualTo(4);
        assertThat(side.files()).extracting(SessionBranchComparison.FileSummary::path)
                .containsExactly("A.java", "B.java", "C.java");
        assertThat(side.files().getFirst().additions()).isEqualTo(15);
        assertThat(side.files().getFirst().deletions()).isEqualTo(3);
        assertThat(side.files().getFirst().changes()).isEqualTo(2);
    }

    @Test
    void capsIntentAndToolLists() {
        List<SessionEntry> segment = new ArrayList<>();
        int seq = 1;
        for (int index = 0; index < 25; index++) {
            segment.add(entry(seq++, new MessagePayload(
                    SessionEntryFactory.user("意图-" + index + "-" + "x".repeat(450)))));
        }
        for (int index = 0; index < 12; index++) {
            segment.add(entry(seq++, new ApprovalPayload("a" + index, "c" + index,
                    "tool-" + index, "DENIED", "no", "user")));
        }

        SessionBranchComparison.BranchSide side = projector.project(segment, LEAF);

        assertThat(side.intents()).hasSize(20);
        assertThat(side.intents().getFirst()).hasSize(400).endsWith("…");
        assertThat(side.approvals().denied()).isEqualTo(12);
        assertThat(side.approvals().deniedTools()).hasSize(10);
        assertThat(side.approvals().deniedTools().getFirst()).isEqualTo("tool-0");
    }

    @Test
    void classifiesFilesByPath() {
        SessionBranchComparison.BranchSide abandoned = side("a.java", "b.java");
        SessionBranchComparison.BranchSide current = side("b.java", "c.java");

        SessionBranchComparison.FileDifference difference = projector.difference(abandoned, current);

        assertThat(difference.onlyAbandoned()).containsExactly("a.java");
        assertThat(difference.onlyCurrent()).containsExactly("c.java");
        assertThat(difference.both()).containsExactly("b.java");
        assertThat(difference.truncated()).isFalse();
    }

    @Test
    void writesDeterministicSummaryForBothSides() {
        List<SessionEntry> abandoned = List.of(
                entry(1, new MessagePayload(SessionEntryFactory.user("旧方案"))),
                entry(2, new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main")),
                entry(3, new ApprovalPayload("a1", "c1", "write", "DENIED", "no", "user")),
                entry(4, changeSet("run-1", 1, file("a.java", 10, 2))),
                entry(5, new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 1, 100, null)));
        List<SessionEntry> current = List.of(
                entry(1, new MessagePayload(SessionEntryFactory.user("新方案"))),
                entry(2, new GitCheckpointPayload("run-2", null, "tree-1", "head-0", "main")),
                entry(3, changeSet("run-2", 1, file("b.java", 1, 0))),
                entry(4, new RunSummaryPayload("run-2", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null)));
        SessionBranchComparison.BranchSide oldSide = projector.project(abandoned, LEAF);
        SessionBranchComparison.BranchSide newSide = projector.project(current, LEAF);
        SessionBranchComparison.FileDifference files = projector.difference(oldSide, newSide);

        String summary = projector.summary(oldSide, newSide, files);

        assertThat(summary).contains("另一条分支：1 轮对话、1 个 run、1 次拒绝")
                .contains("当前分支：1 轮对话、1 个 run")
                .contains("另一条分支独有的文件：a.java")
                .contains("当前分支独有的文件：b.java");
        assertThat(summary.length()).isLessThanOrEqualTo(500);
    }

    @Test
    void describesEmptySide() {
        SessionBranchComparison.BranchSide empty = projector.project(List.of(), null);

        String summary = projector.summary(empty, empty,
                SessionBranchComparison.FileDifference.empty());

        assertThat(summary).isEqualTo("另一条分支没有后续动作。\n当前分支没有后续动作。");
    }

    private static SessionEntry entry(int seq, EntryPayload payload) {
        return SessionEntryFactory.entry(SESSION, seq,
                seq == 1 ? null : SessionEntryFactory.entryId(99 + seq), payload, 0);
    }

    private static GitChangeSetPayload changeSet(String runId, int turn, FileChange... files) {
        return new GitChangeSetPayload(runId, turn, null, "tree-a", "tree-b",
                List.of(files), "diff", false, "");
    }

    private static FileChange file(String path, int additions, int deletions) {
        return new FileChange(path, GitChangeKind.MODIFIED, additions, deletions, false);
    }

    private static SessionBranchComparison.BranchSide side(String... paths) {
        List<SessionBranchComparison.FileSummary> files = new ArrayList<>();
        for (String path : paths) {
            files.add(new SessionBranchComparison.FileSummary(path, 1, 0, 1));
        }
        return new SessionBranchComparison.BranchSide(LEAF, List.of(), 0, 0, 0, 0, List.of(),
                SessionBranchComparison.ApprovalStats.empty(),
                SessionBranchComparison.VerificationStats.empty(),
                SessionBranchComparison.PlanStats.empty(),
                SessionBranchComparison.ChangeStats.empty(), files);
    }
}