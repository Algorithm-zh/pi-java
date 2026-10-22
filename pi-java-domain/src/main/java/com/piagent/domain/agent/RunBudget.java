package com.piagent.domain.agent;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次 Run 的执行预算。
 *
 * <p>预算在 run 开始时冻结，避免运行中修改配置导致同一 run 的终止条件漂移。
 * 轮数限制的是“模型请求次数”，工具调用限制的是“工具执行次数”；超时覆盖
 * 整个 run，包括 LLM 等待与工具执行。</p>
 *
 * @param maxTurns 最大模型请求轮数
 * @param maxToolCalls 最大工具调用次数
 * @param maxDuration 最大运行时长
 */
public record RunBudget(int maxTurns, int maxToolCalls, Duration maxDuration) {

    private static final int DEFAULT_MAX_TURNS = 20;
    private static final int DEFAULT_MAX_TOOL_CALLS = 100;
    private static final Duration DEFAULT_MAX_DURATION = Duration.ofMinutes(15);
    private static final Duration HARD_MAX_DURATION = Duration.ofHours(1);

    /**
     * 校验预算。
     *
     * @param maxTurns 最大轮数
     * @param maxToolCalls 最大工具调用数
     * @param maxDuration 最大时长
     */
    public RunBudget {
        Guard.require(maxTurns >= 1 && maxTurns <= 100, DomainErrorCode.INVALID_ARGUMENT,
                "maxTurns 必须在 1 到 100 之间");
        Guard.require(maxToolCalls >= 0 && maxToolCalls <= 1000, DomainErrorCode.INVALID_ARGUMENT,
                "maxToolCalls 必须在 0 到 1000 之间");
        Objects.requireNonNull(maxDuration, "maxDuration 不能为空");
        Guard.require(!maxDuration.isZero() && !maxDuration.isNegative()
                        && maxDuration.compareTo(HARD_MAX_DURATION) <= 0,
                DomainErrorCode.INVALID_ARGUMENT, "maxDuration 必须在 1 秒到 1 小时之间");
    }

    /**
     * 默认预算：20 轮、100 次工具调用、15 分钟。
     *
     * @return 默认预算
     */
    public static RunBudget defaults() {
        return new RunBudget(DEFAULT_MAX_TURNS, DEFAULT_MAX_TOOL_CALLS, DEFAULT_MAX_DURATION);
    }

    /**
     * 是否已经用完模型轮数。
     *
     * @param turns 已完成的模型请求数
     * @return true 表示不能再发起下一轮
     */
    public boolean turnsExhausted(int turns) {
        return turns >= maxTurns;
    }

    /**
     * 追加指定工具调用后是否会超限。
     *
     * @param currentToolCalls 已执行工具调用数
     * @param requested 本批工具调用数
     * @return true 表示超限
     */
    public boolean toolCallsExceeded(int currentToolCalls, int requested) {
        return (long) currentToolCalls + requested > maxToolCalls;
    }

    /**
     * 是否已超过运行时长。
     *
     * @param startedAt 开始时间
     * @param now 当前时间
     * @return true 表示超时
     */
    public boolean timedOut(Instant startedAt, Instant now) {
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        return Duration.between(startedAt, now).compareTo(maxDuration) > 0;
    }
}
