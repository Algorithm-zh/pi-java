package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.AuthSessionView;

/**
 * 登录成功响应。
 *
 * <p>{@code token} 只在本响应出现一次，供命令行与第三方客户端使用；浏览器
 * 依赖响应同时下发的 HttpOnly Cookie，可忽略该字段。</p>
 *
 * @param token 访问令牌明文
 * @param user 账号与租户信息
 */
public record AuthSessionResponse(String token, AuthUserResponse user) {

    /**
     * 由应用层会话转换。
     *
     * @param session 登录会话
     * @return 响应
     */
    public static AuthSessionResponse from(AuthSessionView session) {
        return new AuthSessionResponse(session.token(), AuthUserResponse.from(session.user()));
    }
}
