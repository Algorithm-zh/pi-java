package com.piagent.application.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.piagent.application.agent.service.RunAuxEvents;
import com.piagent.application.agent.service.RunRuntime;
import com.piagent.application.agent.service.SessionEntryAppender;
import com.piagent.application.testsupport.InMemorySessionEntryStore;
import com.piagent.application.testsupport.InMemorySessionRepository;
import com.piagent.application.testsupport.SequentialIdGenerator;
import com.piagent.domain.plan.model.Plan;
import com.piagent.domain.plan.model.PlanItem;
import com.piagent.domain.plan.model.PlanItemStatus;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.SandboxSnapshot;
import com.piagent.domain.session.model.SessionConfig;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SessionPlanServiceTest {

    private static final Path WORKSPACE = Path.of("target/plan-service-test")
            .toAbsolutePath().normalize();

    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemorySessionEntryStore entries = new InMemorySessionEntryStore();
    private final RunRuntime runtime = mock(RunRuntime.class);
    private final RunAuxEvents events = mock(RunAuxEvents.class);
    private final SequentialIdGenerator ids = new SequentialIdGenerator();
    private final SessionEntryAppender appender = new SessionEntryAppender(sessions, entries, ids,
            Clock.fixed(Instant.parse("2026-10-26T00:00:00Z"), ZoneOffset.UTC));
    private final SessionPlanService service = new SessionPlanService(sessions, entries, appender,
            runtime, events);

    private ChatSession session;

    @BeforeEach
    void setUp() {
        session = ChatSession.createRoot(SessionId.newId(ids), "计划测试",
                new SessionConfig(WORKSPACE, "loop-fake", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(session);
        appender.appendUserAndStart(session.identity().id(), "开始任务");
        when(runtime.findActiveBySession(session.identity().id())).thenReturn(Optional.empty());
    }

    @Test
    void emptySessionHasNoPlanOrVerification() {
        ChatSession fresh = ChatSession.createRoot(SessionId.newId(ids), "空会话",
                new SessionConfig(WORKSPACE, "loop-fake", ThinkingLevel.OFF,
                        SandboxSnapshot.forWorkspace(WORKSPACE)));
        sessions.insert(fresh);

        SessionPlanSnapshot snapshot = service.snapshot(fresh.identity().id());

        assertThat(snapshot.hasPlan()).isFalse();
        assertThat(snapshot.plan().isEmpty()).isTrue();
        assertThat(snapshot.verification()).isNull();
    }

    @Test
    void replaceWritesLatestSnapshotAndPublishesEvent() {
        Plan plan = plan();

        service.replace(session.identity().id(), plan);

        SessionPlanSnapshot snapshot = service.snapshot(session.identity().id());
        assertThat(snapshot.hasPlan()).isTrue();
        assertThat(snapshot.plan()).isEqualTo(plan);
        assertThat(snapshot.completed()).isEqualTo(1);
        ArgumentCaptor<PlanPayload> payload = ArgumentCaptor.forClass(PlanPayload.class);
        verify(events).planUpdated(org.mockito.ArgumentMatchers.eq(session.identity().id()),
                payload.capture());
        assertThat(payload.getValue().items()).isEqualTo(plan.items());
    }

    @Test
    void laterSnapshotSupersedesEarlierPlan() {
        service.replace(session.identity().id(), plan());
        Plan updated = new Plan(List.of(
                new PlanItem("写实现", PlanItemStatus.DONE),
                new PlanItem("跑测试", PlanItemStatus.IN_PROGRESS)));

        service.replace(session.identity().id(), updated);

        assertThat(service.snapshot(session.identity().id()).plan()).isEqualTo(updated);
    }

    @Test
    void snapshotIncludesLatestVerification() {
        service.replace(session.identity().id(), plan());
        appender.appendMetadata(session.identity().id(),
                new VerificationPayload("run-1", "mvn -q test", true, 0, false, false,
                        1800, "BUILD SUCCESS"));

        SessionPlanSnapshot snapshot = service.snapshot(session.identity().id());

        assertThat(snapshot.verification()).isNotNull();
        assertThat(snapshot.verification().passed()).isTrue();
        assertThat(snapshot.verification().command()).isEqualTo("mvn -q test");
    }

    @Test
    void unknownSessionFails() {
        SessionId missing = SessionId.parse(
                com.piagent.domain.shared.Ulid.generate(Instant.parse("2026-01-01T00:00:00Z"))
                        .toString());

        assertThatThrownBy(() -> service.snapshot(missing))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.SESSION_NOT_FOUND));
    }

    private static Plan plan() {
        return new Plan(List.of(
                new PlanItem("读代码", PlanItemStatus.DONE),
                new PlanItem("写实现", PlanItemStatus.IN_PROGRESS),
                new PlanItem("跑测试", PlanItemStatus.PENDING)));
    }
}
