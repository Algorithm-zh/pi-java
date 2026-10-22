package com.piagent.domain.agent;

/**
 * Run 的终止原因。
 *
 * <p>它描述整个 run 为什么结束，而不是单次模型响应为什么停止；后者由
 * {@code AssistantStopReason} 表达。一次 run 可以包含多次 TOOL_USE 响应，
 * 最终仍以 {@link #STOP} 结束。</p>
 */
public enum RunStopReason {

    /** 模型正常给出最终回答。 */
    STOP,

    /** 模型达到输出长度限制且没有后续工具调用。 */
    LENGTH,

    /** 达到最大轮数或最大工具调用数。 */
    MAX_STEPS,

    /** 用户取消。 */
    CANCELLED,

    /** 超过运行时长预算。 */
    TIMEOUT,

    /** 模型、传输或持久化错误。 */
    ERROR
}
