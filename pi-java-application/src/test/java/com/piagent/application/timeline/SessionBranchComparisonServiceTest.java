package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.message.TextBlock;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionBranchComparisonServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-06T10:00:00Z");
    private static final Path WORKSPACE = Path.of("target/comparison-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final SessionBranchComparisonService service = new SessionBranchComparisonService(
            sessions, entries, new BranchComparisonProjector());

    private SessionId sessionId;
    private EntryId leaf;
    private long seq;

    @BeforeEach
    void setUp() {
        sessionId = SessionId.newId(ids);
        sessions.insert(ChatSession.createRoot(sessionId, "对比测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE))));
    }

    @Test
    void reportsUnavailableWhenSessionNeverRewound() {
        append(user("只聊过一次"));
        append(new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main"));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        moveLeaf();

        SessionBranchComparison comparison = service.compare(sessionId, null);

        assertThat(comparison.available()).isFalse();
        assertThat(comparison.branchEntryId()).isNull();
        assertThat(comparison.abandoned().intents()).isEmpty();
        assertThat(comparison.current().intents()).isEmpty();
        assertThat(service.compare(sessionId, SessionEntryFactory.entryId(9999)).available())
                .isFalse();
    }

    @Test
    void comparesAbandonedAndCurrentBranches() {
        append(user("修复登录"));
        append(new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main"));
        append(new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user"));
        append(new VerificationPayload("run-1", "mvn test", false, 1, false, false, 120, "fail"));
        append(change("run-1", "A.java", 10, 2));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 2, 900, null));
        EntryId oldLeaf = leaf;
        EntryId brief = rewindTo(oldLeaf, null, "run-1");

        append(user("改用另一种方式"));
        append(new GitCheckpointPayload("run-2", brief, "tree-1", "head-0", "main"));
        append(change("run-2", "B.java", 3, 1));
        append(new RunSummaryPayload("run-2", "COMPLETED", TokenUsage.zero(), 1, 0, 300, null));
        moveLeaf();

        SessionBranchComparison comparison = service.compare(sessionId, brief);

        assertThat(comparison.available()).isTrue();
        assertThat(comparison.branchEntryId()).isEqualTo(brief);
        assertThat(comparison.forkEntryId()).isNull();
        assertThat(comparison.at()).isEqualTo(
                entries.findById(sessionId, brief).orElseThrow().createdAt());
        assertThat(comparison.abandoned().intents()).containsExactly("修复登录");
        assertThat(comparison.abandoned().runs()).isEqualTo(1);
        assertThat(comparison.abandoned().approvals().denied()).isEqualTo(1);
        assertThat(comparison.abandoned().verifications().failed()).isEqualTo(1);
        assertThat(comparison.abandoned().files())
                .extracting(SessionBranchComparison.FileSummary::path)
                .containsExactly("A.java");
        assertThat(comparison.current().intents()).containsExactly("改用另一种方式");
        assertThat(comparison.current().runs()).isEqualTo(1);
        assertThat(comparison.current().files())
                .extracting(SessionBranchComparison.FileSummary::path)
                .containsExactly("B.java");
        assertThat(comparison.files().onlyAbandoned()).containsExactly("A.java");
        assertThat(comparison.files().onlyCurrent()).containsExactly("B.java");
        assertThat(comparison.files().both()).isEmpty();
        assertThat(comparison.summary()).contains("另一条分支").contains("当前分支");
    }

    @Test
    void comparesAlwaysWithResumeBrief() {
        append(user("旧分支思路"));
        append(new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main"));
        append(change("run-1", "A.java", 10, 2));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 1, 500, null));
        EntryId oldLeaf = leaf;
        EntryId rewindBrief = rewindTo(oldLeaf, null, "run-1");

        append(user("新分支想法"));
        append(new GitCheckpointPayload("run-2", rewindBrief, "tree-1", "head-0", "main"));
        append(change("run-2", "B.java", 3, 1));
        append(new RunSummaryPayload("run-2", "COMPLETED", TokenUsage.zero(), 1, 0, 300, null));
        EntryId leftLeaf = leaf;

        leaf = oldLeaf;
        EntryId resumeBrief = append(new BranchSummaryPayload(leftLeaf, "切回另一条分支",
                Map.of("kind", "resume", "branchEntryId", rewindBrief.toString())));
        moveLeaf();

        SessionBranchComparison comparison = service.compare(sessionId, resumeBrief);

        assertThat(comparison.available()).isTrue();
        assertThat(comparison.branchEntryId()).isEqualTo(resumeBrief);
        assertThat(comparison.abandoned().intents()).containsExactly("新分支想法");
        assertThat(comparison.abandoned().files())
                .extracting(SessionBranchComparison.FileSummary::path)
                .containsExactly("B.java");
        assertThat(comparison.current().files())
                .extracting(SessionBranchComparison.FileSummary::path)
                .containsExactly("A.java");
        assertThat(comparison.files().onlyAbandoned()).containsExactly("B.java");
        assertThat(comparison.files().onlyCurrent()).containsExactly("A.java");
        assertThat(comparison.summary()).contains("另一条分支").contains("当前分支");
    }

    @Test
    void defaultsToLatestRewindAndRejectsOutdatedBrief() {
        append(user("修复登录"));
        append(new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main"));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        EntryId firstLeaf = leaf;
        EntryId firstBrief = rewindTo(firstLeaf, null, "run-1");

        append(user("第二条路"));
        append(new GitCheckpointPayload("run-2", firstBrief, "tree-1", "head-0", "main"));
        append(change("run-2", "B.java", 3, 1));
        append(new RunSummaryPayload("run-2", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        EntryId secondLeaf = leaf;
        EntryId secondBrief = rewindTo(secondLeaf, null, "run-2");

        append(user("第三条路"));
        append(new GitCheckpointPayload("run-3", secondBrief, "tree-2", "head-0", "main"));
        append(new RunSummaryPayload("run-3", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        moveLeaf();

        SessionBranchComparison latest = service.compare(sessionId, null);

        assertThat(latest.branchEntryId()).isEqualTo(secondBrief);
        assertThat(latest.abandoned().intents()).containsExactly("第二条路");
        assertThat(latest.current().intents()).containsExactly("第三条路");
        assertThatThrownBy(() -> service.compare(sessionId, firstBrief))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_BRANCH));
    }

    @Test
    void rejectsUnknownRewindEntry() {
        append(user("修复登录"));
        append(new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main"));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        EntryId oldLeaf = leaf;
        rewindTo(oldLeaf, null, "run-1");

        assertThatThrownBy(() -> service.compare(sessionId, SessionEntryFactory.entryId(9999)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_BRANCH));
    }

    private EntryId rewindTo(EntryId oldLeaf, EntryId baseLeaf, String runId) {
        leaf = baseLeaf;
        EntryId brief = append(new BranchSummaryPayload(oldLeaf, "回退到检查点之前",
                Map.of("kind", "rewind", "runId", runId)));
        moveLeaf();
        return brief;
    }

    private static MessagePayload user(String text) {
        return new MessagePayload(new UserMessage(List.of(new TextBlock(text)), NOW));
    }

    private static GitChangeSetPayload change(String runId, String path, int additions,
            int deletions) {
        return new GitChangeSetPayload(runId, 1, null, "tree-a", "tree-b",
                List.of(new FileChange(path, GitChangeKind.MODIFIED, additions, deletions, false)),
                "diff", false, "");
    }

    private EntryId append(EntryPayload payload) {
        seq++;
        EntryId id = SessionEntryFactory.entryId(500 + (int) seq);
        SessionEntry entry = new SessionEntry(id, sessionId, seq, leaf, payload.entryType(),
                SessionEntry.CURRENT_PAYLOAD_VERSION, payload, 0, NOW.plusMillis(seq));
        entries.append(entry);
        leaf = id;
        return id;
    }

    private void moveLeaf() {
        ChatSession session = sessions.findById(sessionId).orElseThrow();
        sessions.update(session.moveLeaf(leaf));
    }
}