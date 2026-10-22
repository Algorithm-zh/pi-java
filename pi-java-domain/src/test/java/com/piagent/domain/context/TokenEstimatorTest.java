package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.junit.jupiter.api.Test;

/**
 * token 估算与上下文预算测试。
 */
class TokenEstimatorTest {

    private final TokenEstimator estimator = new TokenEstimator();

    @Test
    void estimatesAsciiByFourCharactersPerToken() {
        assertThat(estimator.estimate("hello world")).isEqualTo(TokenEstimate.heuristic(3));
        assertThat(estimator.estimate("abcd")).isEqualTo(TokenEstimate.heuristic(1));
        assertThat(estimator.estimate("")).isEqualTo(TokenEstimate.heuristic(0));
        assertThat(estimator.estimate(null)).isEqualTo(TokenEstimate.heuristic(0));
    }

    @Test
    void estimatesCjkByOneCharacterPerToken() {
        assertThat(estimator.estimate("你好世界")).isEqualTo(TokenEstimate.heuristic(4));
        assertThat(estimator.estimate("你好 world")).isEqualTo(TokenEstimate.heuristic(4));
        assertThat(estimator.estimate("。")).isEqualTo(TokenEstimate.heuristic(1));
    }

    @Test
    void anchorsToProviderUsage() {
        TokenEstimate heuristic = TokenEstimate.heuristic(10);

        assertThat(estimator.anchor(heuristic, 100))
                .isEqualTo(new TokenEstimate(100, TokenEstimate.Method.USAGE_ANCHOR));
        assertThat(estimator.anchor(heuristic, 4)).isEqualTo(heuristic);
        assertThat(estimator.anchor(heuristic, 0)).isEqualTo(heuristic);
    }

    @Test
    void budgetDetectsOverflow() {
        ContextBudget budget = new ContextBudget(100, 20, 30);

        assertThat(budget.availableTokens()).isEqualTo(80);
        assertThat(budget.exceeds(80)).isFalse();
        assertThat(budget.exceeds(81)).isTrue();
        assertThatThrownBy(() -> new ContextBudget(100, 100, 10))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
        assertThatThrownBy(() -> new ContextBudget(100, 20, 80))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }
}
