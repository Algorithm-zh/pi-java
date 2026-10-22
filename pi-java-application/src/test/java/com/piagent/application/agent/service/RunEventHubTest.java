package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 事件缓冲、重放与缺口检测测试。
 */
class RunEventHubTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private RunEventHub hub;
    private RunId runId;
    private SessionId sessionId;

    @BeforeEach
    void setUp() {
        hub = new RunEventHub(Clock.fixed(NOW, ZoneOffset.UTC));
        runId = new RunId(Ulid.generate(NOW));
        sessionId = new SessionId(Ulid.generate(NOW));
        hub.open(runId, sessionId);
    }

    @Test
    void replaysEventsAfterSequence() {
        hub.publish(runId, RunEventType.RUN_STARTED, Map.of("step", 1));
        hub.publish(runId, RunEventType.MESSAGE_DELTA, Map.of("step", 2));
        hub.publish(runId, RunEventType.RUN_COMPLETED, Map.of("step", 3));

        List<RunEvent> all = subscribe(0);
        List<RunEvent> tail = subscribe(2);

        assertThat(hub.lastSeq(runId)).isEqualTo(3);
        assertThat(all).extracting(RunEvent::seq).containsExactly(1L, 2L, 3L);
        assertThat(tail).singleElement().satisfies(event -> {
            assertThat(event.seq()).isEqualTo(3);
            assertThat(event.type()).isEqualTo(RunEventType.RUN_COMPLETED);
            assertThat(event.sessionId()).isEqualTo(sessionId);
        });
    }

    @Test
    void reportsGapWhenBufferDroppedEarlyEvents() {
        for (int index = 1; index <= RunEventHub.BUFFER_CAPACITY + 5; index++) {
            hub.publish(runId, RunEventType.MESSAGE_DELTA, Map.of("index", index));
        }

        List<RunEvent> fromStart = subscribe(0);
        List<RunEvent> contiguous = subscribe(5);

        assertThat(hub.buffered(runId)).isEqualTo(RunEventHub.BUFFER_CAPACITY);
        assertThat(fromStart).hasSize(RunEventHub.BUFFER_CAPACITY + 1);
        assertThat(fromStart.getFirst().type()).isEqualTo(RunEventType.REPLAY_GAP);
        assertThat(fromStart.get(1).seq()).isEqualTo(6);
        assertThat(contiguous).hasSize(RunEventHub.BUFFER_CAPACITY);
        assertThat(contiguous.getFirst().seq()).isEqualTo(6);
        assertThat(contiguous).noneMatch(event -> event.type() == RunEventType.REPLAY_GAP);
    }

    @Test
    void closedChannelStillReplaysBufferedEvents() {
        hub.publish(runId, RunEventType.RUN_STARTED, Map.of());
        hub.close(runId);
        hub.publish(runId, RunEventType.RUN_COMPLETED, Map.of());

        List<RunEvent> replayed = new ArrayList<>();
        RunSubscription subscription = hub.subscribe(runId, 0, replayed::add);

        assertThat(hub.isOpen(runId)).isFalse();
        assertThat(hub.buffered(runId)).isEqualTo(1);
        assertThat(replayed).extracting(RunEvent::type).containsExactly(RunEventType.RUN_STARTED);
        assertThat(subscription.closedAtSubscribe()).isTrue();
        subscription.close();
    }

    @Test
    void unknownOrNegativeSequenceIsRejected() {
        RunId missing = new RunId(Ulid.generate(NOW.plusSeconds(1)));
        List<RunEvent> ignored = new ArrayList<>();

        assertThat(hub.exists(missing)).isFalse();
        assertThatThrownBy(() -> hub.subscribe(missing, 0, ignored::add))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.RUN_NOT_FOUND));
        assertThatThrownBy(() -> hub.subscribe(runId, -1, ignored::add))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    private List<RunEvent> subscribe(long afterSeq) {
        List<RunEvent> events = new ArrayList<>();
        RunSubscription subscription = hub.subscribe(runId, afterSeq, events::add);
        subscription.close();
        return events;
    }
}
