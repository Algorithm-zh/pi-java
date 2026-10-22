package com.piagent.application.agent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.agent.AgentRun;
import com.piagent.domain.agent.RunBudget;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * 进程内 run 注册表的互斥、取消与终态释放测试。
 */
class ActiveRunRegistryTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private final ActiveRunRegistry registry = new ActiveRunRegistry();
    private final SessionId sessionId = new SessionId(Ulid.generate(NOW));

    @Test
    void enforcesSingleActiveRunPerSession() {
        AgentRun first = startRun();
        registry.register(first);

        assertThatThrownBy(() -> registry.register(startRun()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.RUN_ALREADY_ACTIVE));
        assertThat(registry.findActiveBySession(sessionId)).contains(first);
    }

    @Test
    void completingRunReleasesSessionSlot() {
        AgentRun run = startRun();
        registry.register(run);
        AgentRun finished = run.finish(RunStopReason.STOP, null, NOW.plusSeconds(1));
        registry.complete(finished);

        assertThat(registry.findActiveBySession(sessionId)).isEmpty();
        assertThat(registry.awaitTerminal(run.id(), Duration.ofSeconds(1))).isEqualTo(finished);
        AgentRun next = startRun();
        registry.register(next);
        assertThat(registry.findActiveBySession(sessionId)).contains(next);
        assertThat(next).isNotEqualTo(finished);
    }

    @Test
    void cancelMarksRunAndFreesOnlyAfterCompletion() {
        AgentRun run = startRun();
        registry.register(run);
        registry.cancel(run.id());

        assertThat(registry.isCancelled(run.id())).isTrue();
        assertThat(registry.findActiveBySession(sessionId)).contains(run);

        registry.complete(run.finish(RunStopReason.CANCELLED, null, NOW));
        assertThat(registry.findActiveBySession(sessionId)).isEmpty();
    }

    @Test
    void abandonClearsReservedRun() {
        AgentRun run = startRun();
        registry.register(run);
        registry.abandon(run.id());

        assertThat(registry.find(run.id())).isEmpty();
        assertThat(registry.findActiveBySession(sessionId)).isEmpty();
        registry.register(startRun());
    }

    @Test
    void terminalRunCannotBeUpdated() {
        AgentRun run = startRun();
        registry.register(run);
        AgentRun finished = run.finish(RunStopReason.ERROR, "失败", NOW);
        registry.complete(finished);

        assertThatThrownBy(() -> registry.update(run.id(), current -> current))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    private AgentRun startRun() {
        RunId runId = new RunId(Ulid.generate(NOW.plusMillis(System.nanoTime())));
        return AgentRun.start(runId, sessionId, RunBudget.defaults(), NOW);
    }
}
