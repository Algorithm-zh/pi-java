package com.piagent.application.identity.command;

/**
 * 登录命令。
 *
 * @param tenantSlug 租户短名；空表示默认空间
 * @param username 登录名
 * @param password 明文密码
 */
public record LoginCommand(String tenantSlug, String username, String password) {
}
