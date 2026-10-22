package com.piagent.domain.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Run 预算边界测试。
 */
class RunBudgetTest {

    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void defaultsAreFrozenAndReasonable() {
        RunBudget budget = RunBudget.defaults();

        assertThat(budget.maxTurns()).isEqualTo(20);
        assertThat(budget.maxToolCalls()).isEqualTo(100);
        assertThat(budget.maxDuration()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void checksTurnAndToolCallLimitsAtBoundary() {
        RunBudget budget = new RunBudget(3, 2, Duration.ofMinutes(1));

        assertThat(budget.turnsExhausted(2)).isFalse();
        assertThat(budget.turnsExhausted(3)).isTrue();
        assertThat(budget.toolCallsExceeded(1, 1)).isFalse();
        assertThat(budget.toolCallsExceeded(1, 2)).isTrue();
        assertThat(budget.toolCallsExceeded(2, 0)).isFalse();
    }

    @Test
    void timeoutIsStrictlyGreaterThanDuration() {
        RunBudget budget = new RunBudget(3, 2, Duration.ofMinutes(1));

        assertThat(budget.timedOut(START, START.plusSeconds(59))).isFalse();
        assertThat(budget.timedOut(START, START.plusSeconds(60))).isFalse();
        assertThat(budget.timedOut(START, START.plusSeconds(60).plusMillis(1))).isTrue();
    }

    @Test
    void rejectsOutOfRangeConfiguration() {
        assertInvalid(() -> new RunBudget(0, 1, Duration.ofMinutes(1)));
        assertInvalid(() -> new RunBudget(101, 1, Duration.ofMinutes(1)));
        assertInvalid(() -> new RunBudget(1, -1, Duration.ofMinutes(1)));
        assertInvalid(() -> new RunBudget(1, 1001, Duration.ofMinutes(1)));
        assertInvalid(() -> new RunBudget(1, 1, Duration.ZERO));
        assertInvalid(() -> new RunBudget(1, 1, Duration.ofHours(2)));
    }

    private void assertInvalid(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
