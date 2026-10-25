package com.piagent.application.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitConflict;
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
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
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
import org.junit.jupiter.api.Test;

/**
 * Git 检查点与撤销用例的单元测试。
 *
 * <p>用内存仓储与可编程的 {@link WorkspaceGit} 替身验证：检查点/逐轮改动落成
 * 普通 entry、无变化不写记录、最近一次 run 隔离、撤销的“整体成功或完全不动”、
 * 以及 Git 不可用时的降级行为。</p>
 */
class GitCheckpointServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final Path WORKSPACE = Path.of("target/git-application-unit")
            .toAbsolutePath().normalize();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final SessionEntryAppender appender = new SessionEntryAppender(sessions, entries, ids,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private final FakeGit git = new FakeGit();
    private final GitCheckpointService checkpoints = new GitCheckpointService(git, sessions, entries,
            appender);
    private final GitUndoService undo = new GitUndoService(checkpoints, git, sessions, entries);

    @Test
    void recordsCheckpointAndTurnChanges() {
        SessionId sessionId = createSession().identity().id();
        appender.appendUserAndStart(sessionId, "改一下文件");
        RunId runId = RunId.newId(ids);

        git.snapshots.add(new GitSnapshot("tree-0", "head-0", "main"));
        Optional<GitSnapshot> snapshot = checkpoints.beginRun(sessionId, WORKSPACE, runId, null);
        assertThat(snapshot).contains(new GitSnapshot("tree-0", "head-0", "main"));

        git.snapshots.add(new GitSnapshot("tree-1", "head-0", "main"));
        git.nextChanges = changeSet("tree-0", "tree-1", "a.txt");
        Optional<GitChangeSetPayload> change = checkpoints.endTurn(sessionId, WORKSPACE, runId,
                1, null, "tree-0");
        assertThat(change).isPresent();
        assertThat(change.orElseThrow().suggestedCommit()).isNotBlank();

        appender.finishRun(sessionId, summary(runId));
        SessionGitChanges changes = checkpoints.changes(sessionId);

        assertThat(changes.available()).isTrue();
        assertThat(changes.canUndo()).isTrue();
        assertThat(changes.checkpoint().runId()).isEqualTo(runId.toString());
        assertThat(changes.checkpoint().baseLeafId()).isNull();
        assertThat(changes.turns()).singleElement().satisfies(turn -> {
            assertThat(turn.turn()).isEqualTo(1);
            assertThat(turn.files()).extracting(FileChange::path).containsExactly("a.txt");
            assertThat(turn.suggestedCommit()).isNotBlank();
        });
        assertThat(changes.additions()).isEqualTo(3);
        assertThat(changes.deletions()).isEqualTo(1);
        assertThat(changes.fileCount()).isEqualTo(1);

        List<SessionEntry> branch = branch(sessionId);
        assertThat(branch).extracting(SessionEntry::type).containsExactly(EntryType.MESSAGE,
                EntryType.GIT_CHECKPOINT, EntryType.GIT_CHANGES, EntryType.RUN_SUMMARY);
        assertThat(branch).filteredOn(entry -> entry.type() == EntryType.GIT_CHECKPOINT
                        || entry.type() == EntryType.GIT_CHANGES)
                .allSatisfy(entry -> assertThat(entry.tokenEstimate()).isZero());
    }

    @Test
    void skipsTurnWhenSnapshotIsUnchanged() {
        SessionId sessionId = createSession().identity().id();
        appender.appendUserAndStart(sessionId, "什么都不改");
        RunId runId = RunId.newId(ids);

        git.snapshots.add(new GitSnapshot("tree-0", "head-0", "main"));
        checkpoints.beginRun(sessionId, WORKSPACE, runId, null);
        git.snapshots.add(new GitSnapshot("tree-0", "head-0", "main"));

        assertThat(checkpoints.endTurn(sessionId, WORKSPACE, runId, 1, null, "tree-0")).isEmpty();
        assertThat(branch(sessionId)).extracting(SessionEntry::type)
                .containsExactly(EntryType.MESSAGE, EntryType.GIT_CHECKPOINT);
    }

    @Test
    void degradesWhenGitIsUnavailable() {
        SessionId sessionId = createSession().identity().id();
        git.available = false;

        assertThat(checkpoints.beginRun(sessionId, WORKSPACE, RunId.newId(ids), null)).isEmpty();
        assertThat(checkpoints.changes(sessionId)).isEqualTo(SessionGitChanges.EMPTY);
    }

    @Test
    void readsOnlyLatestRun() {
        SessionId sessionId = createSession().identity().id();
        RunId first = RunId.newId(ids);
        appendRun(sessionId, first, "tree-0", "tree-1");
        appender.finishRun(sessionId, summary(first));

        git.snapshots.add(new GitSnapshot("tree-1", "head-0", "main"));
        EntryId baseLeaf = branch(sessionId).getLast().id();
        appender.appendUserAndStart(sessionId, "第二轮");
        RunId second = RunId.newId(ids);
        checkpoints.beginRun(sessionId, WORKSPACE, second, baseLeaf);

        SessionGitChanges changes = checkpoints.changes(sessionId);

        assertThat(changes.checkpoint().runId()).isEqualTo(second.toString());
        assertThat(changes.turns()).isEmpty();
    }

    @Test
    void undoRestoresWorkspaceAndRewindsBranch() {
        SessionId sessionId = createSession().identity().id();
        RunId runId = RunId.newId(ids);
        appendRun(sessionId, runId, "tree-0", "tree-1");
        git.snapshots.add(new GitSnapshot("tree-2", "head-0", "main"));
        git.nextChanges = changeSet("tree-1", "tree-2", "b.txt");
        checkpoints.endTurn(sessionId, WORKSPACE, runId, 2, null, "tree-1");
        appender.finishRun(sessionId, summary(runId));
        git.nextRestore = new GitRestoreResult(List.of("a.txt", "b.txt"), List.of());

        GitUndoResult result = undo.undo(sessionId);

        assertThat(result.rewound()).isTrue();
        assertThat(result.conflicts()).isEmpty();
        assertThat(result.restored()).containsExactly("a.txt", "b.txt");
        assertThat(git.restoreBaseTree).isEqualTo("tree-0");
        assertThat(git.restoreTargetTree).isEqualTo("tree-2");
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isNull();
        assertThat(branch(sessionId)).isEmpty();
    }

    @Test
    void undoFirstTurnClearsLogSoNextRunStartsFromRoot() {
        SessionId sessionId = createSession().identity().id();
        RunId runId = RunId.newId(ids);
        appendRun(sessionId, runId, "tree-0", "tree-1");
        appender.finishRun(sessionId, summary(runId));
        git.nextRestore = new GitRestoreResult(List.of("a.txt"), List.of());

        assertThat(undo.undo(sessionId).rewound()).isTrue();

        SessionEntry user = appender.appendUserAndStart(sessionId, "重来一次");
        assertThat(user.seq()).isEqualTo(1);
        assertThat(user.parentId()).isNull();
        assertThat(branch(sessionId)).extracting(SessionEntry::id).containsExactly(user.id());
    }

    @Test
    void undoConflictKeepsBranchAndWorkspace() {
        SessionId sessionId = createSession().identity().id();
        RunId runId = RunId.newId(ids);
        appendRun(sessionId, runId, "tree-0", "tree-1");
        appender.finishRun(sessionId, summary(runId));
        EntryId leafBefore = sessions.findById(sessionId).orElseThrow().leafEntryId();
        git.nextRestore = new GitRestoreResult(List.of(),
                List.of(new GitConflict("a.txt", "本轮之后工作区又发生了变化，为避免覆盖已跳过")));

        GitUndoResult result = undo.undo(sessionId);

        assertThat(result.rewound()).isFalse();
        assertThat(result.restored()).isEmpty();
        assertThat(result.conflicts()).extracting(GitConflict::path).containsExactly("a.txt");
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId()).isEqualTo(leafBefore);
    }

    @Test
    void undoRejectsRunningSession() {
        SessionId sessionId = createSession().identity().id();
        appender.appendUserAndStart(sessionId, "还在跑");

        assertThatThrownBy(() -> undo.undo(sessionId))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void undoWithoutCheckpointIsUnavailable() {
        SessionId sessionId = createSession().identity().id();

        assertThatThrownBy(() -> undo.undo(sessionId))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.GIT_UNAVAILABLE));
    }

    @Test
    void reportsIdleAfterRunAndKeepsChangesVisible() {
        SessionId sessionId = createSession().identity().id();
        RunId runId = RunId.newId(ids);
        appendRun(sessionId, runId, "tree-0", "tree-1");
        appender.finishRun(sessionId, summary(runId));

        ChatSession session = sessions.findById(sessionId).orElseThrow();

        assertThat(session.status()).isEqualTo(SessionStatus.IDLE);
        assertThat(checkpoints.changes(sessionId).canUndo()).isTrue();
    }

    private void appendRun(SessionId sessionId, RunId runId, String baseTree, String endTree) {
        appender.appendUserAndStart(sessionId, "第一轮");
        git.snapshots.add(new GitSnapshot(baseTree, "head-0", "main"));
        checkpoints.beginRun(sessionId, WORKSPACE, runId, null);
        git.snapshots.add(new GitSnapshot(endTree, "head-0", "main"));
        git.nextChanges = changeSet(baseTree, endTree, "a.txt");
        checkpoints.endTurn(sessionId, WORKSPACE, runId, 1, null, baseTree);
    }

    private ChatSession createSession() {
        ChatSession session = ChatSession.createRoot(SessionId.newId(ids), "Git 测试",
                new SessionConfig(WORKSPACE, "loop-fake", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(session);
        return session;
    }

    private List<SessionEntry> branch(SessionId sessionId) {
        ChatSession session = sessions.findById(sessionId).orElseThrow();
        return entries.loadBranch(sessionId, session.leafEntryId());
    }

    private static RunSummaryPayload summary(RunId runId) {
        return new RunSummaryPayload(runId.toString(), "STOP", TokenUsage.zero(), 1, 0, 800, null);
    }

    private static GitChangeSet changeSet(String baseTree, String endTree, String path) {
        return new GitChangeSet(baseTree, endTree,
                List.of(new FileChange(path, GitChangeKind.MODIFIED, 3, 1, false)),
                "diff --git a/" + path + " b/" + path + "\n+three\n", false);
    }

    /**
     * 可编程的 Git 替身：快照按队列返回，改动与还原结果由测试预设。
     */
    private static final class FakeGit implements WorkspaceGit {

        private final Deque<GitSnapshot> snapshots = new ArrayDeque<>();
        private GitChangeSet nextChanges;
        private GitRestoreResult nextRestore = new GitRestoreResult(List.of(), List.of());
        private String restoreBaseTree;
        private String restoreTargetTree;
        private boolean available = true;

        @Override
        public Optional<GitSnapshot> snapshot(Path workspace) {
            if (!available) {
                return Optional.empty();
            }
            return Optional.ofNullable(snapshots.pollFirst());
        }

        @Override
        public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
            if (nextChanges == null) {
                throw new IllegalStateException("测试未预设改动");
            }
            return nextChanges;
        }

        @Override
        public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
            restoreBaseTree = baseTreeSha;
            restoreTargetTree = treeSha;
            return nextRestore;
        }
    }
}
