package com.piagent.application.identity.command;

/**
 * 自助注册命令；新账号固定加入默认空间且角色为成员。
 *
 * @param username 登录名
 * @param displayName 展示名
 * @param password 明文密码
 */
public record RegisterCommand(String username, String displayName, String password) {
}
