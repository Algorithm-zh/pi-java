package com.piagent.trigger.http;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 领域异常到 HTTP 响应的统一映射。
 *
 * <p>只映射可预期的领域错误；未知异常继续向上冒泡，由框架按 500 处理，
 * 避免把内部细节包装成看似正常的响应。</p>
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /**
     * 映射领域异常。
     *
     * @param error 领域异常
     * @return 错误响应
     */
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainException(DomainException error) {
        return ResponseEntity.status(statusOf(error.code()))
                .body(new ApiErrorResponse(error.code().name(), error.getMessage()));
    }

    /**
     * 请求体缺失或 JSON 非法时返回 400，不暴露解析器内部细节。
     *
     * @param error 解析异常
     * @return 错误响应
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(HttpMessageNotReadableException error) {
        return ResponseEntity.badRequest()
                .body(new ApiErrorResponse(DomainErrorCode.INVALID_ARGUMENT.name(), "请求体缺失或不是合法 JSON"));
    }

    private HttpStatus statusOf(DomainErrorCode code) {
        return switch (code) {
            case MODEL_NOT_FOUND, SESSION_NOT_FOUND, ENTRY_NOT_FOUND, RUN_NOT_FOUND ->
                    HttpStatus.NOT_FOUND;
            case MODEL_ALREADY_EXISTS, CONCURRENT_MODIFICATION, MODEL_DISABLED,
                    INVALID_STATE_TRANSITION, SESSION_ARCHIVED, RUN_ALREADY_ACTIVE ->
                    HttpStatus.CONFLICT;
            case AGENT_BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            default -> HttpStatus.BAD_REQUEST;
        };
    }
}
