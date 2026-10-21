package com.piagent.trigger.http;

/**
 * 统一错误响应。
 *
 * @param code 稳定错误码
 * @param message 可读消息
 */
public record ApiErrorResponse(String code, String message) {
}
