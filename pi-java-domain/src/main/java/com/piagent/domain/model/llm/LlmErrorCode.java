package com.piagent.domain.model.llm;

/**
 * LLM 调用错误分类。
 *
 * <p>重试由适配器在“尚未输出任何 delta”时按 {@link #retryable()} 决定；
 * 应用层只根据错误码决定是否压缩重试或终止 run，不直接重试网络请求。</p>
 */
public enum LlmErrorCode {

    /** 网络连接失败。 */
    NETWORK(true),

    /** 请求超时。 */
    TIMEOUT(true),

    /** 被限流。 */
    RATE_LIMITED(true),

    /** provider 5xx。 */
    SERVER_ERROR(true),

    /** provider 暂时不可用。 */
    PROVIDER_UNAVAILABLE(true),

    /** 认证失败。 */
    AUTHENTICATION(false),

    /** 请求参数被 provider 拒绝。 */
    INVALID_REQUEST(false),

    /** 上下文超出窗口。 */
    CONTEXT_OVERFLOW(false),

    /** 响应格式不合法。 */
    MALFORMED_RESPONSE(false),

    /** 缺少 API Key 环境变量。 */
    MISSING_API_KEY(false),

    /** 不支持的 provider。 */
    UNSUPPORTED_PROVIDER(false),

    /** 调用被取消。 */
    CANCELLED(false);

    private final boolean retryable;

    LlmErrorCode(boolean retryable) {
        this.retryable = retryable;
    }

    /**
     * 是否允许在输出首个 delta 前重试。
     *
     * @return true 表示可重试
     */
    public boolean retryable() {
        return retryable;
    }
}
