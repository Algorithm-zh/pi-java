package com.piagent.trigger.http.system;

/**
 * 健康检查响应。
 *
 * @param status 存活状态
 */
public record HealthResponse(String status) {
}
