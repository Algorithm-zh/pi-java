package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.RegisterCommand;

/**
 * 自助注册请求。
 *
 * @param username 登录名
 * @param displayName 展示名
 * @param password 明文密码
 */
public record RegisterRequest(String username, String displayName, String password) {

    /**
     * 转注册命令。
     *
     * @return 命令
     */
    public RegisterCommand toCommand() {
        return new RegisterCommand(username, displayName, password);
    }
}
