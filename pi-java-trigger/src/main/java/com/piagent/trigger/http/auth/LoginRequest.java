package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.LoginCommand;

/**
 * 登录请求。
 *
 * @param tenantSlug 目标空间短名；留空时按成员关系自动定位，多个空间需要点选
 * @param username 登录名
 * @param password 明文密码
 */
public record LoginRequest(String tenantSlug, String username, String password) {

    /**
     * 转登录命令。
     *
     * @return 命令
     */
    public LoginCommand toCommand() {
        return new LoginCommand(tenantSlug, username, password);
    }
}
