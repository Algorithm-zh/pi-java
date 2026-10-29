package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.CreateUserCommand;
import com.piagent.domain.identity.model.UserRole;

/**
 * 创建账号请求。
 *
 * @param username 登录名
 * @param displayName 展示名，可为空
 * @param password 明文密码
 * @param role 角色
 */
public record CreateUserRequest(String username, String displayName, String password,
        UserRole role) {

    /**
     * 转创建命令。
     *
     * @return 命令
     */
    public CreateUserCommand toCommand() {
        return new CreateUserCommand(username, displayName, password, role);
    }
}
