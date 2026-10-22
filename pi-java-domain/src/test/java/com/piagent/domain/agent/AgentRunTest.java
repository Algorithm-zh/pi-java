package com.piagent.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Ulid;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Run 快照的状态机与预算边界测试。
 */
class AgentRunTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END = START.plusSeconds(42);
    private static final RunId RUN_ID = new RunId(Ulid.generate(START));
    private static final SessionId SESSION_ID = new SessionId(Ulid.generate(START));

    @Test
    void startsRunningWithZeroCounters() {
        AgentRun run = AgentRun.start(RUN_ID, SESSION_ID, RunBudget.defaults(), START);

        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.isTerminal()).isFalse();
        assertThat(run.turns()).isZero();
        assertThat(run.toolCalls()).isZero();
        assertThat(run.usage()).isEqualTo(TokenUsage.zero());
        assertThat(run.finishedAt()).isNull();
        assertThat(run.stopReason()).isNull();
    }

    @Test
    void accumulatesTurnsUsageAndToolCalls() {
        AgentRun run = AgentRun.start(RUN_ID, SESSION_ID, RunBudget.defaults(), START)
                .recordTurn(new TokenUsage(10, 5, 2, 1, 18))
                .recordTurn(new TokenUsage(20, 7, 0, 0, 27))
                .recordToolCalls(3);

        assertThat(run.turns()).isEqualTo(2);
        assertThat(run.toolCalls()).isEqualTo(3);
        assertThat(run.usage()).isEqualTo(new TokenUsage(30, 12, 2, 1, 45));
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void mapsStopReasonsToTerminalStatus() {
        assertFinished(RunStopReason.STOP, RunStatus.COMPLETED);
        assertFinished(RunStopReason.LENGTH, RunStatus.COMPLETED);
        assertFinished(RunStopReason.MAX_STEPS, RunStatus.COMPLETED);
        assertFinished(RunStopReason.CANCELLED, RunStatus.ABORTED);
        assertFinished(RunStopReason.TIMEOUT, RunStatus.ABORTED);
        assertFinished(RunStopReason.ERROR, RunStatus.FAILED);
    }

    @Test
    void failedRunRequiresErrorMessage() {
        AgentRun run = AgentRun.start(RUN_ID, SESSION_ID, RunBudget.defaults(), START);

        assertThatThrownBy(() -> run.finish(RunStopReason.ERROR, "  ", END))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void terminalRunIsImmutable() {
        AgentRun finished = AgentRun.start(RUN_ID, SESSION_ID, RunBudget.defaults(), START)
                .finish(RunStopReason.STOP, null, END);

        assertThat(finished.isTerminal()).isTrue();
        assertThat(finished.durationMillis(null)).isEqualTo(42_000);
        assertThatThrownBy(() -> finished.finish(RunStopReason.STOP, null, END))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
        assertThatThrownBy(() -> finished.recordTurn(TokenUsage.zero()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_STATE_TRANSITION));
    }

    @Test
    void runningDurationUsesProvidedNow() {
        AgentRun run = AgentRun.start(RUN_ID, SESSION_ID, RunBudget.defaults(), START);

        assertThat(run.durationMillis(START.plus(Duration.ofMillis(1500)))).isEqualTo(1500);
        assertThat(run.durationMillis(START.minusSeconds(5))).isZero();
        assertThatThrownBy(() -> run.durationMillis(null)).isInstanceOf(NullPointerException.class);
    }

    private void assertFinished(RunStopReason reason, RunStatus expected) {
        String error = reason == RunStopReason.ERROR ? "模型失败" : null;
        AgentRun finished = AgentRun.start(RUN_ID, SESSION_ID, RunBudget.defaults(), START)
                .finish(reason, error, END);

        assertThat(finished.status()).isEqualTo(expected);
        assertThat(finished.stopReason()).isEqualTo(reason);
        assertThat(finished.finishedAt()).isEqualTo(END);
        assertThat(finished.isTerminal()).isTrue();
        if (error != null) {
            assertThat(finished.errorMessage()).isEqualTo(error);
        }
    }
}
