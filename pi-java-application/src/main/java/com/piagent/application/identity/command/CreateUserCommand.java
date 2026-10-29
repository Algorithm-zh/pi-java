package com.piagent.application.identity.command;

import com.piagent.domain.identity.model.UserRole;

/**
 * 在当前租户内创建账号。
 *
 * @param username 登录名
 * @param displayName 展示名，可为空
 * @param password 明文密码
 * @param role 角色
 */
public record CreateUserCommand(String username, String displayName, String password, UserRole role) {
}
