package com.piagent.domain.context;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 上下文预算。
 *
 * <p>{@code reserveTokens} 为模型输出与协议开销预留；实际可用于输入的额度是
 * {@code contextWindow - reserveTokens}。压缩时保留最近 {@code keepRecentTokens}
 * 的上下文，避免摘要把正在进行的任务细节全部抹掉。</p>
 *
 * @param contextWindow 模型上下文窗口
 * @param reserveTokens 输出与协议预留
 * @param keepRecentTokens 压缩后至少保留的近期 token
 */
public record ContextBudget(int contextWindow, int reserveTokens, int keepRecentTokens) {

    /**
     * 校验预算。
     *
     * @param contextWindow 上下文窗口
     * @param reserveTokens 预留 token
     * @param keepRecentTokens 保留近期 token
     */
    public ContextBudget {
        Guard.require(contextWindow > 0, DomainErrorCode.INVALID_ARGUMENT,
                "contextWindow 必须大于 0");
        Guard.require(reserveTokens >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "reserveTokens 不能为负数");
        Guard.require(reserveTokens < contextWindow, DomainErrorCode.INVALID_ARGUMENT,
                "reserveTokens 必须小于 contextWindow");
        Guard.require(keepRecentTokens >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "keepRecentTokens 不能为负数");
        Guard.require(keepRecentTokens < availableTokens(contextWindow, reserveTokens),
                DomainErrorCode.INVALID_ARGUMENT,
                "keepRecentTokens 必须小于可用输入额度");
    }

    /**
     * 可用输入额度。
     *
     * @return contextWindow - reserveTokens
     */
    public int availableTokens() {
        return availableTokens(contextWindow, reserveTokens);
    }

    /**
     * 是否需要压缩。
     *
     * @param estimatedTokens 当前上下文估算
     * @return true 表示超过可用额度
     */
    public boolean exceeds(int estimatedTokens) {
        return estimatedTokens > availableTokens();
    }

    private static int availableTokens(int contextWindow, int reserveTokens) {
        return contextWindow - reserveTokens;
    }
}
