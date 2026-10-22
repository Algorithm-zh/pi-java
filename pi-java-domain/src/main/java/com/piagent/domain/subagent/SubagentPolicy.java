package com.piagent.domain.subagent;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 子代理策略：限制子代理能做什么。
 *
 * <p>为什么需要独立策略对象：子代理最大的风险是“越权”——用父会话的权限做父会话
 * 不该做或没被批准的事，或者以指数级递归消耗额度。把限制集中到一个不可变值对象里，
 * 应用层只需按策略执行，测试也能直接断言边界。</p>
 *
 * <p>三条规则：</p>
 * <ol>
 *   <li><b>深度</b>：达到 {@code maxDepth} 时，子代理的工具目录里<b>不再包含</b>
 *       {@code subagent} 工具。这是“移除工具”而不是“运行时报错”，
 *       让模型从目录上就知道不能再分派；</li>
 *   <li><b>工具子集</b>：子代理可用工具 = 父快照 ∩ 请求白名单。
 *       白名单为空表示继承全部（仍受深度与沙箱约束），不允许扩大；</li>
 *   <li><b>超时</b>：子代理有独立的执行上限，超时视为 {@code ABORTED}。</li>
 * </ol>
 *
 * @param maxDepth 允许的最大子代理深度（用户会话为 0，其直接子代理为 1）
 * @param defaultTimeoutSeconds 子代理默认超时
 * @param maxTimeoutSeconds 子代理允许的最大超时
 */
public record SubagentPolicy(int maxDepth, int defaultTimeoutSeconds, int maxTimeoutSeconds) {

    /** 默认最大深度：用户 → 子 → 孙，再往下不再允许。 */
    public static final int DEFAULT_MAX_DEPTH = 2;

    /**
     * 子代理允许的最长执行时间上限（秒）。
     *
     * <p>与工具调度器的硬上限保持一致：{@code ToolDescriptor} 限制单次工具执行不超过
     * 10 分钟，子代理作为工具同步运行，超过这个时长会被调度器中断。策略值不能再大，
     * 否则会出现“配置看上去生效、实际被静默截断”的情况。</p>
     */
    public static final int MAX_TIMEOUT_SECONDS = 600;

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * 校验策略。
     *
     * @param maxDepth 最大深度
     * @param defaultTimeoutSeconds 默认超时
     * @param maxTimeoutSeconds 最大超时
     */
    public SubagentPolicy {
        Guard.require(maxDepth >= 1, DomainErrorCode.INVALID_ARGUMENT,
                "maxDepth 必须大于等于 1");
        Guard.require(defaultTimeoutSeconds >= 10, DomainErrorCode.INVALID_ARGUMENT,
                "defaultTimeoutSeconds 不能小于 10");
        Guard.require(maxTimeoutSeconds >= defaultTimeoutSeconds, DomainErrorCode.INVALID_ARGUMENT,
                "maxTimeoutSeconds 不能小于 defaultTimeoutSeconds");
        Guard.require(maxTimeoutSeconds <= MAX_TIMEOUT_SECONDS, DomainErrorCode.INVALID_ARGUMENT,
                "maxTimeoutSeconds 不能超过工具调度上限 " + MAX_TIMEOUT_SECONDS + " 秒");
    }

    /**
     * 默认策略。
     *
     * @return 策略
     */
    public static SubagentPolicy defaults() {
        return new SubagentPolicy(DEFAULT_MAX_DEPTH, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
    }

    /**
     * 创建自定义深度策略，超时使用默认值。
     *
     * @param maxDepth 最大深度
     * @return 策略
     */
    public static SubagentPolicy withMaxDepth(int maxDepth) {
        return new SubagentPolicy(maxDepth, DEFAULT_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS);
    }
    /**
     * 给定父会话深度，是否还允许再创建子代理。
     *
     * @param parentDepth 父会话深度（用户会话为 0）
     * @return true 表示子代理工具的深度为 parentDepth + 1，不超过上限
     */
    public boolean allowsChildOf(int parentDepth) {
        return parentDepth + 1 <= maxDepth;
    }

    /**
     * 解析请求的超时。
     *
     * <p>未指定或非正数时用默认值；超过上限时直接拒绝，而不是静默截断——
     * 静默截断会让调用方以为自己的超时生效了，实际却在更早的时间点被终止。</p>
     *
     * @param requestedSeconds 请求超时；null 表示未指定
     * @return 生效超时秒数
     */
    public int resolveTimeoutSeconds(Integer requestedSeconds) {
        if (requestedSeconds == null || requestedSeconds <= 0) {
            return defaultTimeoutSeconds;
        }
        Guard.require(requestedSeconds <= maxTimeoutSeconds, DomainErrorCode.INVALID_ARGUMENT,
                "子代理超时不能超过 " + maxTimeoutSeconds + " 秒");
        return requestedSeconds;
    }
}
