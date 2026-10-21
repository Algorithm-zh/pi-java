package com.piagent.domain.session.model.message;

/**
 * 单次模型响应的停止原因。
 *
 * <p>它是 assistant 消息的一部分；Run 的终止原因由 agent 上下文另行映射，
 * 两者不能混用（例如一次 run 可以包含多个 {@link #TOOL_USE} 的模型响应）。
 */
public enum AssistantStopReason {

    /** 正常结束。 */
    STOP,

    /** 达到输出长度限制。 */
    LENGTH,

    /** 要求执行工具。 */
    TOOL_USE,

    /** 模型或传输错误。 */
    ERROR,

    /** 被取消。 */
    ABORTED
}
