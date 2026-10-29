package com.piagent.application.identity.readmodel;

/**
 * 登录成功后的会话信息。
 *
 * @param token 访问令牌明文；只在本次响应出现
 * @param user 账号与租户信息
 */
public record AuthSessionView(String token, AuthUserView user) {
}
