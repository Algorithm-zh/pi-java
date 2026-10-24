package com.piagent.infrastructure.mcp;

/**
 * MCP 协议或传输失败。
 *
 * <p>错误码用于工具结果细节与日志，取值固定为 {@code MCP_TRANSPORT}、
 * {@code MCP_TIMEOUT}、{@code MCP_PROTOCOL}、{@code MCP_STREAM_CLOSED}、
 * {@code MCP_CANCELLED}。</p>
 */
public final class McpException extends RuntimeException {

    private final String errorCode;

    /**
     * 创建异常。
     *
     * @param errorCode 错误码
     * @param message 消息
     */
    public McpException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    /**
     * 创建带原因的异常。
     *
     * @param errorCode 错误码
     * @param message 消息
     * @param cause 原因
     */
    public McpException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    /**
     * 错误码。
     *
     * @return 错误码
     */
    public String errorCode() {
        return errorCode;
    }
}
