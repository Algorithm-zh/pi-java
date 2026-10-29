package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.SetupCommand;

/**
 * 首次初始化请求。
 *
 * @param username 管理员登录名
 * @param displayName 展示名，可为空
 * @param password 明文密码
 */
public record SetupRequest(String username, String displayName, String password) {

    /**
     * 转初始化命令。
     *
     * @return 命令
     */
    public SetupCommand toCommand() {
        return new SetupCommand(username, displayName, password);
    }
}
