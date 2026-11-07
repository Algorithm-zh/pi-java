package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionBranchResumeServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-07T10:00:00Z");
    private static final Path WORKSPACE = Path.of("target/resume-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final BranchSummaryAppender summaries = new BranchSummaryAppender(sessions, entries,
            ids, Clock.fixed(NOW, ZoneOffset.UTC));
    private final FakeGit git = new FakeGit();
    private final SessionBranchResumeService service = new SessionBranchResumeService(sessions,
            entries, git, summaries, new BranchResumeBriefBuilder());

    private SessionId sessionId;
    private EntryId leaf;
    private long seq;

    @BeforeEach
    void setUp() {
        sessionId = SessionId.newId(ids);
        sessions.insert(ChatSession.createRoot(sessionId, "切换测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE))));
    }

    @Test
    void previewsTargetTreeAndCurrentTree() {
        Fixture fixture = twoBranches();

        BranchResumeResult result = service.resume(sessionId, fixture.rewindBrief(), true, null);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.switched()).isFalse();
        assertThat(result.available()).isTrue();
        assertThat(result.restorable()).isTrue();
        assertThat(result.files()).containsExactly("b.txt");
        assertThat(result.leftEntries()).isEqualTo(5);
        assertThat(result.leftRuns()).isEqualTo(1);
        assertThat(result.targetEntries()).isEqualTo(4);
        assertThat(result.targetRuns()).isEqualTo(2);
        assertThat(git.lastPlanTarget).isEqualTo("tree-a2");
        assertThat(git.lastPlanLatest).isEqualTo("tree-b1");
        assertThat(result.brief()).contains("切回另一条分支");
        assertThat(entries.countBySession(sessionId)).isEqualTo(fixture.entryCount());
    }

    @Test
    void switchesLeafAppendsBriefAndRestoresWorkspace() {
        Fixture fixture = twoBranches();
        git.snapshots.add(new GitSnapshot("tree-live-b", "head", "main"));
        git.nextRestore = new GitRestoreResult(List.of("b.txt"), List.of());

        BranchResumeResult result = service.resume(sessionId, fixture.rewindBrief(), false,
                "先跑测试");

        assertThat(result.switched()).isTrue();
        assertThat(result.targetLeafId()).isEqualTo(fixture.targetLeaf());
        assertThat(result.restored()).containsExactly("b.txt");
        SessionEntry brief = entries.findById(sessionId, result.briefEntryId()).orElseThrow();
        assertThat(brief.parentId()).isEqualTo(fixture.targetLeaf());
        BranchSummaryPayload payload = (BranchSummaryPayload) brief.payload();
        assertThat(payload.details())
                .containsEntry("kind", "resume")
                .containsEntry("branchEntryId", fixture.rewindBrief().toString())
                .containsEntry("fromTreeSha", "tree-live-b")
                .containsEntry("toTreeSha", "tree-a2")
                .containsEntry("leftEntries", 5)
                .containsEntry("leftRuns", 1)
                .containsEntry("targetEntries", 4)
                .containsEntry("targetRuns", 2);
        assertThat(payload.summary()).contains("备注：先跑测试");
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
        assertThat(git.lastRestoreTarget).isEqualTo("tree-a2");
        assertThat(git.lastRestoreLatest).isEqualTo("tree-b1");
        assertThat(git.pins).contains(
                new FakeGit.Pin(sessionId.toString(), "branch-" + fixture.leftLeaf(),
                        "tree-live-b"),
                new FakeGit.Pin(sessionId.toString(), "branch-" + fixture.targetLeaf(),
                        "tree-a2"));
    }

    @Test
    void switchesBackSymmetric() {
        Fixture fixture = twoBranches();
        git.snapshots.add(new GitSnapshot("tree-live-b", "head", "main"));
        git.nextRestore = new GitRestoreResult(List.of("b.txt"), List.of());
        BranchResumeResult first = service.resume(sessionId, fixture.rewindBrief(), false, null);

        git.snapshots.add(new GitSnapshot("tree-live-a", "head", "main"));
        git.nextPlan = new GitRestorePlan("tree-live-b", "tree-a2", List.of("b.txt"),
                List.of());
        BranchResumeResult second = service.resume(sessionId, first.briefEntryId(), false, null);

        assertThat(second.switched()).isTrue();
        assertThat(second.targetLeafId()).isEqualTo(fixture.leftLeaf());
        SessionEntry brief = entries.findById(sessionId, second.briefEntryId()).orElseThrow();
        BranchSummaryPayload payload = (BranchSummaryPayload) brief.payload();
        assertThat(payload.details())
                .containsEntry("kind", "resume")
                .containsEntry("branchEntryId", first.briefEntryId().toString())
                .containsEntry("fromTreeSha", "tree-live-a")
                .containsEntry("toTreeSha", "tree-live-b");
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
    }

    @Test
    void conflictKeepsEverythingUntouched() {
        Fixture fixture = twoBranches();
        long before = entries.countBySession(sessionId);
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-a2", "tree-b1", List.of("b.txt"),
                List.of(new GitConflict("b.txt", "又变了")));

        BranchResumeResult result = service.resume(sessionId, fixture.rewindBrief(), false, null);

        assertThat(result.restorable()).isFalse();
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.leftEntries()).isEqualTo(5);
        assertThat(result.targetEntries()).isEqualTo(4);
        assertThat(entries.countBySession(sessionId)).isEqualTo(before);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
        assertThat(git.restoreCalled).isFalse();
    }

    @Test
    void reportsUnavailableWhenTargetTreeMissing() {
        EntryId root = append(new MessagePayload(SessionEntryFactory.user("共同起点")));
        EntryId target = append(new MessagePayload(SessionEntryFactory.user("目标没有树")));
        moveLeaf(root);
        EntryId brief = append(new BranchSummaryPayload(target, "切回没有树的分支",
                Map.of("kind", "resume", "toTreeSha", "tree-current")));
        append(new MessagePayload(SessionEntryFactory.user("当前有树")));
        append(new GitChangeSetPayload("run-c", 1, null, "tree-c0", "tree-current",
                List.of(), "diff", false, ""));
        moveLeaf(brief);

        BranchResumeResult result = service.resume(sessionId, brief, true, null);

        assertThat(result.available()).isFalse();
        assertThat(result.restorable()).isFalse();
        assertThat(result.unavailableReason()).contains("目标分支没有可用的工作区快照");
        assertThat(result.brief()).isEmpty();
    }

    @Test
    void rejectsTargetOnCurrentBranchAndNonBranchEntries() {
        Fixture fixture = twoBranches();
        EntryId plain = append(new MessagePayload(SessionEntryFactory.user("普通记录")));

        assertThatThrownBy(() -> service.resume(sessionId, plain, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));
        assertThatThrownBy(() -> service.resume(sessionId, SessionEntryFactory.entryId(9999),
                true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));

        EntryId self = append(new BranchSummaryPayload(leaf, "指向当前叶子",
                Map.of("kind", "resume")));
        assertThatThrownBy(() -> service.resume(sessionId, self, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));

        EntryId missing = append(new BranchSummaryPayload(
                SessionEntryFactory.entryId(8888), "指向不存在的叶子",
                Map.of("kind", "rewind")));
        assertThatThrownBy(() -> service.resume(sessionId, missing, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));
    }

    @Test
    void rejectsRunningArchivedAndTooLongNote() {
        Fixture fixture = twoBranches();
        ChatSession running = sessions.findById(sessionId).orElseThrow().markRunning();
        sessions.update(running);
        assertThatThrownBy(() -> service.resume(sessionId, fixture.rewindBrief(), true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));

        sessions.update(sessions.findById(sessionId).orElseThrow().markIdle().archive());
        assertThatThrownBy(() -> service.resume(sessionId, fixture.rewindBrief(), true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.SESSION_ARCHIVED));
    }

    @Test
    void rejectsTooLongNoteBeforePlanning() {
        Fixture fixture = twoBranches();
        assertThatThrownBy(() -> service.resume(sessionId, fixture.rewindBrief(), true,
                "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThat(git.lastPlanTarget).isNull();
    }

    private Fixture twoBranches() {
        EntryId a1 = append(new MessagePayload(SessionEntryFactory.user("修复登录")));
        EntryId cp1 = append(new GitCheckpointPayload("run-a1", null, "tree-a0",
                "head-0", "main"));
        EntryId a2 = append(new MessagePayload(SessionEntryFactory.user("补测试")));
        EntryId cp2 = append(new GitCheckpointPayload("run-a2", a2, "tree-a1",
                "head-1", "main"));
        EntryId targetLeaf = append(new MessagePayload(SessionEntryFactory.user("继续")));
        moveLeaf(a1);
        EntryId rewindBrief = append(new BranchSummaryPayload(targetLeaf, "回退到第一次之前",
                Map.of("kind", "rewind", "fromTreeSha", "tree-a2", "toTreeSha", "tree-a0",
                        "discardedEntries", 4, "discardedRuns", 2)));
        append(new MessagePayload(SessionEntryFactory.user("新思路")));
        append(new GitCheckpointPayload("run-b1", null, "tree-a0", "head-0", "main"));
        append(new GitChangeSetPayload("run-b1", 1, null, "tree-a0", "tree-b1",
                List.of(), "diff", false, ""));
        EntryId leftLeaf = append(new MessagePayload(SessionEntryFactory.user("再改一点")));
        git.nextPlan = new GitRestorePlan("tree-a2", "tree-b1", List.of("b.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of("b.txt"), List.of());
        return new Fixture(targetLeaf, leftLeaf, rewindBrief, entries.countBySession(sessionId));
    }

    private EntryId append(EntryPayload payload) {
        seq++;
        EntryId id = SessionEntryFactory.entryId(700 + (int) seq);
        SessionEntry entry = new SessionEntry(id, sessionId, seq, leaf, payload.entryType(),
                SessionEntry.CURRENT_PAYLOAD_VERSION, payload, 0, NOW.plusMillis(seq));
        entries.append(entry);
        leaf = id;
        sessions.update(sessions.findById(sessionId).orElseThrow().rewind(id));
        return id;
    }

    private void moveLeaf(EntryId target) {
        sessions.update(sessions.findById(sessionId).orElseThrow().rewind(target));
        leaf = target;
    }

    private record Fixture(EntryId targetLeaf, EntryId leftLeaf, EntryId rewindBrief,
            long entryCount) {
    }

    private static final class FakeGit implements WorkspaceGit {

        private final Deque<GitSnapshot> snapshots = new ArrayDeque<>();
        private final List<Pin> pins = new ArrayList<>();
        private GitRestorePlan nextPlan = new GitRestorePlan("tree", "tree", List.of(), List.of());
        private GitRestoreResult nextRestore = new GitRestoreResult(List.of(), List.of());
        private boolean restoreCalled;
        private String lastPlanTarget;
        private String lastPlanLatest;
        private String lastRestoreTarget;
        private String lastRestoreLatest;

        @Override
        public Optional<GitSnapshot> snapshot(Path workspace) {
            return Optional.ofNullable(snapshots.pollFirst());
        }

        @Override
        public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
            return null;
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            restoreCalled = true;
            lastRestoreTarget = baseTreeSha;
            lastRestoreLatest = treeSha;
            return nextRestore;
        }

        @Override
        public GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
            lastPlanTarget = baseTreeSha;
            lastPlanLatest = treeSha;
            return nextPlan;
        }

        @Override
        public void pinCheckpoint(Path workspace, String namespace, String checkpointId,
                String treeSha) {
            pins.add(new Pin(namespace, checkpointId, treeSha));
        }

        @Override
        public void clearCheckpoints(Path workspace, String namespace) {
        }

        private record Pin(String namespace, String checkpointId, String treeSha) {
        }
    }
}
