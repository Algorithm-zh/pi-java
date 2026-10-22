package com.piagent.domain.context;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 压缩结果是否“收敛”的判定。
 *
 * <p>收敛的含义：压缩后进入模型上下文的 token 必须明显小于压缩前，否则这次压缩
 * 只是在消耗模型调用而没有释放空间。判定采用三个门槛，任一不满足都视为未收敛：</p>
 * <ol>
 *   <li>压缩后不超过预算的可用额度；</li>
 *   <li>压缩后不超过原上下文的 {@code targetRatio}（默认 70%），留出继续对话的余量；</li>
 *   <li>被摘要的 token 必须大于摘要本身的 token（否则摘要比原文还长，毫无意义）。</li>
 * </ol>
 *
 * <p>未收敛时调用方不应反复压缩：应在一次尝试后降级为“只剪枝 + 保留尾部”，
 * 并把 {@code prunedOnly} 写进 COMPACTION entry 的 details，留下可审计的痕迹。</p>
 *
 * @param tokensAfter 压缩后上下文估算
 * @param tokensBefore 压缩前上下文估算
 * @param summarizedTokens 被摘要原文的估算
 * @param summaryTokens 摘要文本的估算
 */
public record CompactionOutcome(
        int tokensAfter,
        int tokensBefore,
        int summarizedTokens,
        int summaryTokens) {

    /** 默认目标比例：压缩后不超过压缩前的 70%。 */
    public static final double DEFAULT_TARGET_RATIO = 0.7;

    /**
     * 校验结果。
     *
     * @param tokensAfter 压缩后
     * @param tokensBefore 压缩前
     * @param summarizedTokens 被摘要原文
     * @param summaryTokens 摘要文本
     */
    public CompactionOutcome {
        Guard.require(tokensAfter >= 0, DomainErrorCode.INVALID_ARGUMENT, "tokensAfter 不能为负数");
        Guard.require(tokensBefore >= 0, DomainErrorCode.INVALID_ARGUMENT, "tokensBefore 不能为负数");
        Guard.require(summarizedTokens >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "summarizedTokens 不能为负数");
        Guard.require(summaryTokens >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "summaryTokens 不能为负数");
    }

    /**
     * 是否收敛。
     *
     * @param budget 上下文预算
     * @return true 表示压缩有效
     */
    public boolean converged(ContextBudget budget) {
        return converged(budget, DEFAULT_TARGET_RATIO);
    }

    /**
     * 是否收敛。
     *
     * @param budget 上下文预算
     * @param targetRatio 目标比例，取值 (0, 1]
     * @return true 表示压缩有效
     */
    public boolean converged(ContextBudget budget, double targetRatio) {
        Guard.require(targetRatio > 0 && targetRatio <= 1, DomainErrorCode.INVALID_ARGUMENT,
                "targetRatio 必须在 (0, 1] 区间");
        if (summaryTokens >= summarizedTokens) {
            return false;
        }
        if (budget.exceeds(tokensAfter)) {
            return false;
        }
        return tokensAfter <= (long) Math.floor(tokensBefore * targetRatio);
    }

    /**
     * 压缩节省的 token；压缩后反而更长时返回 0。
     *
     * @return 节省量
     */
    public int savedTokens() {
        return Math.max(0, tokensBefore - tokensAfter);
    }
}
