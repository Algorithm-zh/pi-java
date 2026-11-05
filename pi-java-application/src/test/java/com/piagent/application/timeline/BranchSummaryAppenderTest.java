package com.piagent.application.timeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.application.testsupport.SessionEntryFactory;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.RunSummaryPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BranchSummaryAppenderTest {

    private static final Instant NOW = Instant.parse("2026-11-05T00:00:00Z");
    private static final Path WORKSPACE = Path.of("target/branch-summary-unit").toAbsolutePath();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SessionEntryAppender runs = new SessionEntryAppender(sessions, entries, ids, clock);
    private final BranchSummaryAppender summaries = new BranchSummaryAppender(sessions, entries, ids,
            clock);

    @Test
    void appendsBriefAsRootWhenBranchIsEmpty() {
        SessionId sessionId = createSession().identity().id();

        SessionEntry brief = summaries.append(sessionId,
                new BranchSummaryPayload(SessionEntryFactory.entryId(999), "回溯简报",
                        Map.of("kind", "rewind")));

        assertThat(brief.seq()).isEqualTo(1);
        assertThat(brief.parentId()).isNull();
        assertThat(brief.type()).isEqualTo(EntryType.BRANCH_SUMMARY);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
    }

    @Test
    void appendsBriefAfterCurrentLeaf() {
        SessionId sessionId = createSession().identity().id();
        SessionEntry user = runs.appendUserAndStart(sessionId, "第一轮");
        SessionEntry summary = runs.finishRun(sessionId, new RunSummaryPayload("run-1", "STOP",
                TokenUsage.zero(), 1, 0, 800, null));

        SessionEntry brief = summaries.append(sessionId,
                new BranchSummaryPayload(user.id(), "回溯简报", Map.of("kind", "rewind")));

        assertThat(brief.parentId()).isEqualTo(summary.id());
        assertThat(brief.seq()).isEqualTo(3);
        assertThat(sessions.findById(sessionId).orElseThrow().leafEntryId())
                .isEqualTo(brief.id());
    }

    @Test
    void rejectsBriefWhileRunning() {
        SessionId sessionId = createSession().identity().id();
        SessionEntry user = runs.appendUserAndStart(sessionId, "还在跑");

        assertThatThrownBy(() -> summaries.append(sessionId,
                new BranchSummaryPayload(user.id(), "回溯简报", Map.of())))
                .isInstanceOfSatisfying(DomainException.class, error ->
                        assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    private ChatSession createSession() {
        ChatSession session = ChatSession.createRoot(SessionId.newId(ids), "摘要追加器测试",
                new SessionConfig(WORKSPACE, "fake-demo", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(session);
        return session;
    }
}
