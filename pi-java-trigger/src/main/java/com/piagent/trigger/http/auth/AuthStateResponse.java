package com.piagent.trigger.http.auth;

/**
 * 登录状态响应。
 *
 * @param initialized 是否已初始化管理员
 * @param authenticated 当前请求是否已登录
 * @param user 当前账号；未登录时为 null
 */
public record AuthStateResponse(boolean initialized, boolean authenticated,
        AuthUserResponse user) {
}
