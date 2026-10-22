package com.piagent.domain.context;

import java.util.Objects;

/**
 * 压缩触发策略。
 *
 * <p>职责单一：只回答“现在该不该压缩、为什么”，不负责切点计算（那是
 * {@link CompactionPlanner} 的职责），也不负责调用模型（那是应用层）。</p>
 *
 * <p>需要压缩的判断依据：</p>
 * <ol>
 *   <li>上下文估算已超过 {@link ContextBudget#availableTokens()}；</li>
 *   <li>并且上一次压缩之后新增的 token 已达到 {@code minGrowthTokens}，
 *       防止“刚压缩完、上下文仍略超预算”时每个 turn 都触发一次无意义压缩。</li>
 * </ol>
 *
 * <p>纯函数、无状态。</p>
 */
public final class CompactionTriggerPolicy {

    /** 默认最小增长量：两次压缩之间至少要新增 2k token，才允许再次压缩。 */
    public static final int DEFAULT_MIN_GROWTH_TOKENS = 2_000;

    private final int minGrowthTokens;

    /**
     * 创建策略。
     *
     * @param minGrowthTokens 两次压缩之间的最小新增 token
     */
    public CompactionTriggerPolicy(int minGrowthTokens) {
        this.minGrowthTokens = Math.max(0, minGrowthTokens);
    }

    /**
     * 创建使用默认参数的策略。
     */
    public CompactionTriggerPolicy() {
        this(DEFAULT_MIN_GROWTH_TOKENS);
    }

    /**
     * 是否应触发自动压缩。
     *
     * @param estimate 当前上下文估算（已做 usage 锚定）
     * @param budget 上下文预算
     * @param lastCompactedTokens 上一次压缩时的上下文估算；从未压缩时为 0
     * @return 判定结果
     */
    public CompactionDecision decide(TokenEstimate estimate, ContextBudget budget,
            int lastCompactedTokens) {
        Objects.requireNonNull(estimate, "estimate 不能为空");
        Objects.requireNonNull(budget, "budget 不能为空");
        int tokens = estimate.tokens();
        if (!budget.exceeds(tokens)) {
            return CompactionDecision.skip("上下文仍在预算内", tokens);
        }
        int growth = tokens - Math.max(0, lastCompactedTokens);
        if (lastCompactedTokens > 0 && growth < minGrowthTokens) {
            return CompactionDecision.skip("上一次压缩后新增内容不足 " + minGrowthTokens + " token",
                    tokens);
        }
        return CompactionDecision.trigger("上下文 " + tokens + " token 超过可用额度 "
                + budget.availableTokens() + " token", tokens);
    }

    /**
     * 两次压缩之间的最小新增 token。
     *
     * @return 阈值
     */
    public int minGrowthTokens() {
        return minGrowthTokens;
    }
}
