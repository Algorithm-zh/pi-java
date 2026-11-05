package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.SessionStatus;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.ApprovalPayload;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.EntryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.session.model.message.UserMessage;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SessionTimelineServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");
    private static final Path WORKSPACE = Path.of("target/timeline-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final SessionTimelineService service = new SessionTimelineService(sessions, entries);

    private SessionId sessionId;
    private EntryId leaf;
    private long seq;

    @BeforeEach
    void setUp() {
        sessionId = SessionId.newId(ids);
        sessions.insert(ChatSession.createRoot(sessionId, "时间线测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE))));
    }

    @Test
    void projectsCheckpointNodesWithEvidence() {
        EntryId run1 = append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("修复登录")), NOW)));
        EntryId checkpoint1 = append(new GitCheckpointPayload("run-1", null, "tree-0",
                "head-0", "main"));
        append(new PlanPayload("run-1", List.of(
                new PlanItem("改登录", PlanItemStatus.DONE),
                new PlanItem("补测试", PlanItemStatus.PENDING))));
        append(new ApprovalPayload("a1", "c1", "write", "DENIED", "先别改", "user"));
        append(new VerificationPayload("run-1", "mvn test", false, 1, false, false, 120, "fail"));
        append(new GitChangeSetPayload("run-1", 1, run1, "tree-0", "tree-1",
                List.of(new FileChange("A.java", GitChangeKind.MODIFIED, 10, 2, false)),
                "diff", false, "fix: login"));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 2, 900, null));
        EntryId run2 = append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("补测试")), NOW)));
        append(new GitCheckpointPayload("run-2", leaf, "tree-1", "head-0", "main"));
        append(new VerificationPayload("run-2", "mvn test", true, 0, false, false, 80, "ok"));
        append(new RunSummaryPayload("run-2", "COMPLETED", TokenUsage.zero(), 1, 0, 300, null));
        moveLeaf();

        SessionTimeline timeline = service.timeline(sessionId);

        assertThat(timeline.available()).isTrue();
        assertThat(timeline.nodes()).hasSize(2);
        SessionTimeline.Node first = timeline.nodes().getFirst();
        assertThat(first.checkpointEntryId()).isEqualTo(checkpoint1);
        assertThat(first.run().intent()).isEqualTo("修复登录");
        assertThat(first.run().stats().plan().total()).isEqualTo(2);
        assertThat(first.run().stats().plan().done()).isEqualTo(1);
        assertThat(first.run().stats().approvals().denied()).isEqualTo(1);
        assertThat(first.run().stats().verification().failed()).isEqualTo(1);
        assertThat(first.run().stats().changes().files()).isEqualTo(1);
        assertThat(first.run().stats().changes().additions()).isEqualTo(10);
        assertThat(first.current()).isFalse();
        assertThat(timeline.nodes().getLast().current()).isTrue();
        assertThat(timeline.currentCheckpointEntryId())
                .isEqualTo(timeline.nodes().getLast().checkpointEntryId());
        assertThat(run2).isNotNull();
    }

    @Test
    void insertsRewindNodeAtItsPositionAndClearsCurrentCheckpoint() {
        append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("第一次")), NOW)));
        append(new GitCheckpointPayload("run-1", null, "tree-0", "head-0", "main"));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        EntryId oldLeaf = leaf;
        append(new BranchSummaryPayload(oldLeaf, "从 10:00 的决策点回退：丢弃 1 轮对话。",
                Map.of("kind", "rewind", "checkpointEntryId", oldLeaf.toString(),
                        "runId", "run-1", "discardedEntries", 3, "discardedRuns", 1)));
        moveLeaf();

        SessionTimeline timeline = service.timeline(sessionId);

        assertThat(timeline.nodes()).hasSize(2);
        assertThat(timeline.nodes().getFirst().current()).isFalse();
        assertThat(timeline.nodes().getLast().kind())
                .isEqualTo(SessionTimeline.NodeKind.REWIND);
        assertThat(timeline.nodes().getLast().current()).isTrue();
        assertThat(timeline.nodes().getLast().rewind().discardedEntries()).isEqualTo(3);
        assertThat(timeline.nodes().getLast().rewind().discardedRuns()).isEqualTo(1);
        assertThat(timeline.currentCheckpointEntryId()).isNull();
    }

    @Test
    void fallsBackToRunNodesWithoutCheckpoints() {
        append(new MessagePayload(new UserMessage(
                List.of(new com.piagent.domain.session.model.message.TextBlock("无 Git 会话")), NOW)));
        append(new RunSummaryPayload("run-1", "COMPLETED", TokenUsage.zero(), 1, 0, 100, null));
        moveLeaf();

        SessionTimeline timeline = service.timeline(sessionId);

        assertThat(timeline.available()).isFalse();
        assertThat(timeline.nodes()).singleElement().satisfies(node -> {
            assertThat(node.kind()).isEqualTo(SessionTimeline.NodeKind.RUN);
            assertThat(node.checkpointEntryId()).isNull();
            assertThat(node.rewindable()).isFalse();
            assertThat(node.run().intent()).isEqualTo("无 Git 会话");
        });
    }

    @Test
    void returnsEmptyTimelineForEmptySession() {
        assertThat(service.timeline(sessionId).nodes()).isEmpty();
        assertThat(service.timeline(sessionId).available()).isFalse();
        assertThat(service.timeline(sessionId).currentCheckpointEntryId()).isNull();
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
