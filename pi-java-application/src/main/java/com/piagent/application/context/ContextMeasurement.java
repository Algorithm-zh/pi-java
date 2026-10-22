package com.piagent.application.context;

import com.piagent.domain.context.ContextBudget;
import com.piagent.domain.context.TokenEstimate;

/**
 * 一次上下文测量的结果。
 *
 * <p>{@code estimate} 已按最近一次的 provider usage 做过锚定；{@code branchTokens}
 * 是纯投影内容的估算，用于日志与排查两者偏差（usage 锚定后通常会更大）。</p>
 *
 * @param estimate 当前上下文估算
 * @param budget 当前上下文预算
 * @param branchTokens 纯投影估算 token，未经 usage 锚定
 */
public record ContextMeasurement(TokenEstimate estimate, ContextBudget budget, int branchTokens) {

    /**
     * 是否超过预算。
     *
     * @return true 表示需要压缩
     */
    public boolean exceeds() {
        return budget.exceeds(estimate.tokens());
    }
}
