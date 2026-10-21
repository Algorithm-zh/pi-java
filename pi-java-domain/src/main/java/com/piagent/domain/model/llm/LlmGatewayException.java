package com.piagent.domain.model.llm;

import java.util.Objects;

/**
 * LLM 网关异常。
 *
 * <p>异常消息不得包含 API Key、完整请求头或未脱敏的用户参数。应用层捕获后
 * 根据错误码映射为 assistant 错误消息或 run 终止原因。</p>
 */
public class LlmGatewayException extends RuntimeException {

    private final LlmErrorCode code;

    /**
     * 创建网关异常。
     *
     * @param code 错误分类
     * @param message 可读消息
     */
    public LlmGatewayException(LlmErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code 不能为空");
    }

    /**
     * 创建带底层原因的网关异常。
     *
     * @param code 错误分类
     * @param message 可读消息
     * @param cause 底层原因
     */
    public LlmGatewayException(LlmErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code 不能为空");
    }

    /**
     * 返回错误分类。
     *
     * @return 错误码
     */
    public LlmErrorCode code() {
        return code;
    }

    /**
     * 是否可在首个 delta 前重试。
     *
     * @return true 表示可重试
     */
    public boolean retryable() {
        return code.retryable();
    }
}
