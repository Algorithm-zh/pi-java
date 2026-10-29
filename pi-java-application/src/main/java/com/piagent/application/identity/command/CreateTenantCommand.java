package com.piagent.application.identity.command;

/**
 * 创建租户及其首个管理员。
 *
 * @param slug 租户短名
 * @param name 租户名称
 * @param username 管理员登录名
 * @param displayName 管理员展示名，可为空
 * @param password 管理员明文密码
 */
public record CreateTenantCommand(String slug, String name, String username, String displayName,
        String password) {
}
