package com.piagent.domain.shared;

import java.util.Objects;

/**
 * 领域规则被违反时抛出的异常。
 *
 * <p>异常携带稳定错误码，应用层据此映射响应；消息应为中文、可读、可直接展示。
 * 领域层不吞异常，也不把技术细节（SQL、HTTP）写进消息。
 */
public class DomainException extends RuntimeException {

    private final DomainErrorCode code;

    /**
     * 创建领域异常。
     *
     * @param code 稳定错误码
     * @param message 可读消息
     */
    public DomainException(DomainErrorCode code, String message) {
        super(message);
        this.code = Objects.requireNonNull(code, "code 不能为空");
    }

    /**
     * 创建带原因的领域异常。
     *
     * @param code 稳定错误码
     * @param message 可读消息
     * @param cause 底层原因
     */
    public DomainException(DomainErrorCode code, String message, Throwable cause) {
        super(message, cause);
        this.code = Objects.requireNonNull(code, "code 不能为空");
    }

    /**
     * 返回稳定错误码。
     *
     * @return 错误码
     */
    public DomainErrorCode code() {
        return code;
    }
}
