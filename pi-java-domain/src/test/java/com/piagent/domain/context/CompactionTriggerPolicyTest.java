package com.piagent.domain.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 压缩触发策略测试。
 */
class CompactionTriggerPolicyTest {

    private final CompactionTriggerPolicy policy = new CompactionTriggerPolicy(100);

    @Test
    void skipsWhenWithinBudget() {
        CompactionDecision decision = policy.decide(TokenEstimate.heuristic(50),
                new ContextBudget(100, 10, 10), 0);

        assertThat(decision.trigger()).isFalse();
        assertThat(decision.reason()).contains("预算内");
    }

    @Test
    void triggersOnFirstOverflow() {
        CompactionDecision decision = policy.decide(TokenEstimate.heuristic(120),
                new ContextBudget(100, 10, 10), 0);

        assertThat(decision.trigger()).isTrue();
        assertThat(decision.tokens()).isEqualTo(120);
        assertThat(decision.reason()).contains("超过可用额度");
    }

    @Test
    void skipsWhenGrowthSinceLastCompactionTooSmall() {
        CompactionDecision decision = policy.decide(TokenEstimate.heuristic(130),
                new ContextBudget(100, 10, 10), 100);

        assertThat(decision.trigger()).isFalse();
        assertThat(decision.reason()).contains("新增内容不足");
    }

    @Test
    void triggersAgainAfterEnoughGrowth() {
        CompactionDecision decision = policy.decide(TokenEstimate.heuristic(260),
                new ContextBudget(100, 10, 10), 100);

        assertThat(decision.trigger()).isTrue();
    }

    @Test
    void anchorsUsageWhenLargerThanHeuristic() {
        TokenEstimate heuristic = TokenEstimate.heuristic(10);
        TokenEstimate anchored = new TokenEstimator().anchor(heuristic, 500);

        assertThat(anchored.tokens()).isEqualTo(500);
        assertThat(anchored.method()).isEqualTo(TokenEstimate.Method.USAGE_ANCHOR);
    }
}
