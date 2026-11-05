package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.git.GitCheckpointService;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionRewindServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T14:00:00Z");
    private static final Path WORKSPACE = Path.of("target/rewind-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SessionEntryAppender appender = new SessionEntryAppender(sessions, entries, ids,
            clock);
    private final FakeGit git = new FakeGit();
    private final GitCheckpointService checkpoints = new GitCheckpointService(git, sessions,
            entries, appender);
    private final BranchSummaryAppender summaries = new BranchSummaryAppender(sessions, entries, ids,
            clock);
    private final SessionRewindService service = new SessionRewindService(sessions, entries, git,
            summaries, new RewindBriefBuilder(clock));

    private SessionId sessionId;

    @BeforeEach
    void setUp() {
        sessionId = SessionId.newId(ids);
        sessions.insert(ChatSession.createRoot(sessionId, "回退测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE))));
    }

    @Test
    void previewDoesNotWriteOrAppend() {
        RunIds runs = threeRuns();
        long before = entries.countBySession(sessionId);
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2",
                List.of("a.txt"), List.of());

        SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, true, null);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.rewound()).isFalse();
        assertThat(result.restorable()).isTrue();
        assertThat(result.files()).containsExactly("a.txt");
        assertThat(result.discardedEntries()).isPositive();
        assertThat(result.discardedRuns()).isPositive();
        assertThat(entries.countBySession(sessionId)).isEqualTo(before);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
        assertThat(git.restoreCalled).isFalse();
        assertThat(git.lastPlanTarget).isEqualTo("tree-1");
        // 快照队列整体滞后一次 poll：endTurn 读到的树等于本轮起点，改动 entry 未落盘；
        // 第二个检查点之后最后一个已知树是第三轮检查点的起点 tree-2，而非 fallback 的 tree-1。
        assertThat(git.lastPlanLatest).isEqualTo("tree-2");
        assertThat(git.lastRestoreTarget).isNull();
        assertThat(git.lastRestoreLatest).isNull();
    }

    @Test
    void rewindsToMiddleCheckpointAndAppendsBrief() {
        RunIds runs = threeRuns();
        EntryId oldLeaf = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2",
                List.of("a.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of("a.txt"), List.of());

        SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, false, null);

        assertThat(result.rewound()).isTrue();
        assertThat(result.restored()).containsExactly("a.txt");
        assertThat(git.lastPlanTarget).isEqualTo("tree-1");
        assertThat(git.lastPlanLatest).isEqualTo("tree-2");
        assertThat(git.lastRestoreTarget).isEqualTo("tree-1");
        assertThat(git.lastRestoreLatest).isEqualTo("tree-2");
        SessionEntry brief = entries.findById(sessionId, result.briefEntryId()).orElseThrow();
        assertThat(brief.type()).isEqualTo(EntryType.BRANCH_SUMMARY);
        assertThat(brief.parentId()).isEqualTo(runs.secondBaseLeaf);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(brief.id());
        assertThat(brief.payload().toString()).contains("kind=rewind");
        assertThat(entries.countBySession(sessionId))
                .isGreaterThan((long) branchSize() - 1);
        List<SessionEntry> branch = entries.loadBranch(sessionId, brief.id());
        assertThat(branch).contains(brief);
        assertThat(branch).noneMatch(entry -> entry.id().equals(runs.thirdCheckpoint));
        assertThat(entries.findById(sessionId, runs.thirdCheckpoint)).isPresent();
        assertThat(entries.findById(sessionId, oldLeaf)).isPresent();
        assertThat(oldLeaf).isNotEqualTo(brief.id());
    }

    @Test
    void rewindsFirstRunToRootAndKeepsDiscardedHistory() {
        RunIds runs = threeRuns();
        long before = entries.countBySession(sessionId);
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2", List.of("a.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of("a.txt"), List.of());

        SessionRewindResult result = service.rewind(sessionId, runs.firstCheckpoint, false,
                "换一种修法");

        assertThat(result.rewound()).isTrue();
        assertThat(entries.countBySession(sessionId)).isEqualTo(before + 1);
        SessionEntry brief = entries.findById(sessionId, result.briefEntryId()).orElseThrow();
        assertThat(brief.seq()).isGreaterThan(1L);
        assertThat(brief.parentId()).isNull();
        assertThat(entries.loadBranch(sessionId, brief.id())).containsExactly(brief);
        assertThat(entries.findById(sessionId, runs.firstCheckpoint)).isPresent();
        assertThat(entries.findById(sessionId, runs.thirdCheckpoint)).isPresent();
        assertThat(result.brief()).contains("备注：换一种修法");
    }

    @Test
    void truncatesSessionBeforeWritingFiles() {
        RunIds runs = threeRuns();
        EntryId oldLeaf = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2", List.of("a.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of("a.txt"), List.of());
        git.beforeRestore = () -> assertThat(
                sessions.findById(sessionId).orElseThrow().leafEntryId())
                .as("会话截断必须先于工作区写入")
                .isNotEqualTo(oldLeaf);

        SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, false,
                null);

        assertThat(result.rewound()).isTrue();
    }

    @Test
    void rejectsConflictDiscoveredDuringExecution() {
        RunIds runs = threeRuns();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2", List.of("a.txt"), List.of());
        git.nextRestore = new GitRestoreResult(List.of(),
                List.of(new GitConflict("a.txt", "确认后工作区又发生了变化")));

        assertThatThrownBy(() -> service.rewind(sessionId, runs.secondCheckpoint, false, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.GIT_OPERATION_FAILED));
    }

    @Test
    void conflictKeepsEverythingUntouched() {
        RunIds runs = threeRuns();
        long before = entries.countBySession(sessionId);
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextPlan = new GitRestorePlan("tree-0", "tree-2", List.of("a.txt"),
                List.of(new GitConflict("a.txt", "目标之后工作区又发生了变化，为避免覆盖已跳过")));

        SessionRewindResult result = service.rewind(sessionId, runs.secondCheckpoint, false, null);

        assertThat(result.rewound()).isFalse();
        assertThat(result.restorable()).isFalse();
        assertThat(result.conflicts()).hasSize(1);
        assertThat(result.discardedEntries()).isZero();
        assertThat(entries.countBySession(sessionId)).isEqualTo(before);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
        assertThat(git.restoreCalled).isFalse();
    }

    @Test
    void rejectsRunningArchivedAndForeignCheckpoint() {
        RunIds runs = threeRuns();
        EntryId foreign = EntryId.parse("01TST000000000000000000099");
        assertThatThrownBy(() -> service.rewind(sessionId, foreign, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));

        ChatSession archived = sessions.findById(sessionId).orElseThrow().archive();
        sessions.update(archived);
        assertThatThrownBy(() -> service.rewind(sessionId, runs.secondCheckpoint, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.SESSION_ARCHIVED));
    }

    @Test
    void rejectsWhileRunning() {
        RunIds runs = threeRuns();
        EntryId baseLeaf = entryIdBeforeUser();
        appender.appendUserAndStart(sessionId, "又开始跑");
        checkpoints.beginRun(sessionId, WORKSPACE, RunId.newId(ids), baseLeaf);

        assertThatThrownBy(() -> service.rewind(sessionId, runs.secondCheckpoint, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void rejectsNonCheckpointEntryOnBranch() {
        threeRuns();
        EntryId summaryEntry = branch().stream()
                .filter(entry -> entry.payload() instanceof RunSummaryPayload)
                .map(SessionEntry::id)
                .findFirst().orElseThrow();

        assertThatThrownBy(() -> service.rewind(sessionId, summaryEntry, true, null))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_BRANCH));
        assertThat(git.lastPlanTarget).isNull();
    }

    @Test
    void rejectsTooLongNoteWithoutRestoring() {
        RunIds runs = threeRuns();
        long before = entries.countBySession(sessionId);
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();

        assertThatThrownBy(() -> service.rewind(sessionId, runs.secondCheckpoint, false,
                "x".repeat(201)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThat(git.restoreCalled).isFalse();
        assertThat(entries.countBySession(sessionId)).isEqualTo(before);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
    }

    private EntryId entryIdBeforeUser() {
        return sessions.findById(sessionId).orElseThrow().leafEntryId();
    }

    private RunIds threeRuns() {
        git.snapshots.add(new GitSnapshot("tree-0", "head", "main"));
        appender.appendUserAndStart(sessionId, "第一轮");
        RunId run1 = RunId.newId(ids);
        EntryId firstCheckpoint = begin(run1, null, "tree-0", "tree-1");
        EntryId firstLeaf = finish(run1);

        appender.appendUserAndStart(sessionId, "第二轮");
        RunId run2 = RunId.newId(ids);
        EntryId secondCheckpoint = begin(run2, firstLeaf, "tree-1", "tree-2");
        EntryId secondLeaf = finish(run2);

        appender.appendUserAndStart(sessionId, "第三轮");
        RunId run3 = RunId.newId(ids);
        EntryId thirdCheckpoint = begin(run3, secondLeaf, "tree-2", "tree-3");
        finish(run3);
        return new RunIds(firstCheckpoint, firstLeaf, secondCheckpoint, thirdCheckpoint);
    }

    private EntryId begin(RunId runId, EntryId baseLeaf, String baseTree, String endTree) {
        git.snapshots.add(new GitSnapshot(baseTree, "head", "main"));
        checkpoints.beginRun(sessionId, WORKSPACE, runId, baseLeaf);
        git.snapshots.add(new GitSnapshot(endTree, "head", "main"));
        git.nextChanges = new GitChangeSet(baseTree, endTree,
                List.of(new FileChange("a.txt", GitChangeKind.MODIFIED, 1, 0, false)),
                "diff", false);
        checkpoints.endTurn(sessionId, WORKSPACE, runId, 1, baseLeaf, baseTree);
        return branch().stream().filter(entry -> entry.payload()
                instanceof GitCheckpointPayload checkpoint
                && checkpoint.runId().equals(runId.toString())).findFirst().orElseThrow().id();
    }

    private EntryId finish(RunId runId) {
        appender.finishRun(sessionId, new RunSummaryPayload(runId.toString(), "COMPLETED",
                TokenUsage.zero(), 1, 0, 100, null));
        return branch().getLast().id();
    }

    private List<SessionEntry> branch() {
        return entries.loadBranch(sessionId,
                sessions.findById(sessionId).orElseThrow().leafEntryId());
    }

    private int branchSize() {
        return branch().size();
    }

    private record RunIds(EntryId firstCheckpoint, EntryId secondBaseLeaf,
            EntryId secondCheckpoint, EntryId thirdCheckpoint) {
    }

    private static final class FakeGit implements WorkspaceGit {

        private final Deque<GitSnapshot> snapshots = new ArrayDeque<>();
        private GitChangeSet nextChanges;
        private GitRestorePlan nextPlan = new GitRestorePlan("tree-0", "tree-0",
                List.of(), List.of());
        private GitRestoreResult nextRestore = new GitRestoreResult(List.of(), List.of());
        private boolean restoreCalled;
        private Runnable beforeRestore;
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
            return nextChanges;
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            if (beforeRestore != null) {
                beforeRestore.run();
            }
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
        }

        @Override
        public void clearCheckpoints(Path workspace, String namespace) {
        }
    }
}
