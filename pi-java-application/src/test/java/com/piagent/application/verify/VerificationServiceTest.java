package com.piagent.application.verify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.piagent.application.agent.service.RunAuxEvents;
import com.piagent.application.agent.service.RunRuntime;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.UserMessage;
import com.piagent.domain.session.model.payload.MessagePayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.verify.model.VerificationOutcome;
import com.piagent.domain.verify.port.VerificationRunner;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VerificationServiceTest {

    private static final Path WORKSPACE = Path.of("target/verify-service-test")
            .toAbsolutePath().normalize();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final RunRuntime runtime = mock(RunRuntime.class);
    private final RunAuxEvents events = mock(RunAuxEvents.class);
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final SessionEntryAppender appender = new SessionEntryAppender(sessions, entries, ids,
            Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC));
    private final RecordingRunner runner = new RecordingRunner();

    private ChatSession session;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        session = ChatSession.createRoot(SessionId.newId(ids), "验证测试",
                new SessionConfig(WORKSPACE, "loop-fake", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(session);
        appender.appendUserAndStart(session.identity().id(), "改点东西");
        when(runtime.findActiveBySession(session.identity().id())).thenReturn(Optional.empty());
        service = new VerificationService(runner, appender, runtime, events,
                new VerificationOptions("mvn -q test", 30, 1000, true),
                Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void verifyRunsConfiguredCommandAndPersistsResult() {
        runner.outcome = new VerificationOutcome("mvn -q test", 0, false, false, 1200,
                "BUILD SUCCESS");

        VerificationPayload payload = service.verify(session.identity().id(), WORKSPACE);

        assertThat(runner.command).isEqualTo("mvn -q test");
        assertThat(runner.workspace).isEqualTo(WORKSPACE);
        assertThat(runner.timeout).isEqualTo(Duration.ofSeconds(30));
        assertThat(runner.maxChars).isEqualTo(1000);
        assertThat(payload.passed()).isTrue();
        assertThat(payload.exitCode()).isZero();
        assertThat(entriesFor(session.identity().id(), EntryType.VERIFICATION))
                .singleElement()
                .satisfies(entry -> assertThat(((VerificationPayload) entry.payload()).output())
                        .isEqualTo("BUILD SUCCESS"));
        verify(events).verificationCompleted(session.identity().id(), payload);
    }

    @Test
    void appendFollowUpWritesFailureContextAsUserMessage() {
        VerificationPayload payload = new VerificationPayload("run-1", "mvn -q test",
                false, 1, false, false, 900, "Tests run: 3, Failures: 1\nat AppTest.java:12");

        service.appendFollowUp(session.identity().id(), payload);

        List<SessionEntry> branch = branch(session.identity().id());
        SessionEntry last = branch.get(branch.size() - 1);
        assertThat(last.type()).isEqualTo(EntryType.MESSAGE);
        UserMessage message = (UserMessage) ((MessagePayload) last.payload()).message();
        String text = ((com.piagent.domain.session.model.message.TextBlock)
                message.content().get(0)).text();
        assertThat(text).contains("自动验证未通过", "mvn -q test", "退出码 1",
                "Tests run: 3, Failures: 1", "请先修复失败原因");
    }

    @Test
    void appendFollowUpTreatsTimeoutAndTruncatesLongOutput() {
        VerificationPayload payload = new VerificationPayload("run-1", "sleep 300",
                false, null, true, true, 30000, "x".repeat(7000));

        service.appendFollowUp(session.identity().id(), payload);

        List<SessionEntry> branch = branch(session.identity().id());
        UserMessage message = (UserMessage) ((MessagePayload) branch.get(branch.size() - 1)
                .payload()).message();
        String text = ((com.piagent.domain.session.model.message.TextBlock)
                message.content().get(0)).text();
        assertThat(text).contains("执行超时", "输出已截断");
        assertThat(text).hasSizeLessThan(6_200);
    }

    @Test
    void disabledOptionsSkipExecutionEnablement() {
        VerificationService disabled = new VerificationService(runner, appender, runtime, events,
                VerificationOptions.disabled(), Clock.systemUTC());

        assertThat(disabled.enabled()).isFalse();
        assertThat(service.enabled()).isTrue();
        assertThat(service.retryOnFailure()).isTrue();
    }

    private List<SessionEntry> entriesFor(SessionId sessionId, EntryType type) {
        return branch(sessionId).stream().filter(entry -> entry.type() == type).toList();
    }

    private List<SessionEntry> branch(SessionId sessionId) {
        ChatSession current = sessions.findById(sessionId).orElseThrow();
        return entries.loadBranch(sessionId, current.leafEntryId());
    }

    private static final class RecordingRunner implements VerificationRunner {

        private VerificationOutcome outcome;
        private String command;
        private Path workspace;
        private Duration timeout;
        private int maxChars;

        @Override
        public VerificationOutcome run(String command, Path workspace, Duration timeout,
                int maxOutputChars) {
            this.command = command;
            this.workspace = workspace;
            this.timeout = timeout;
            this.maxChars = maxOutputChars;
            return outcome;
        }
    }
}
