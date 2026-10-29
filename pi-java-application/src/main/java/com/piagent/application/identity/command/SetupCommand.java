package com.piagent.application.identity.command;

/**
 * 首次初始化管理员。
 *
 * @param username 登录名
 * @param displayName 展示名，可为空
 * @param password 明文密码
 */
public record SetupCommand(String username, String displayName, String password) {
}
