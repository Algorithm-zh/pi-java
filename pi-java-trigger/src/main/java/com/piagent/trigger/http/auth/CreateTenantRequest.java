package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.CreateTenantCommand;

/**
 * 创建租户请求。
 *
 * @param slug 租户短名
 * @param name 租户名称
 * @param username 首个管理员登录名
 * @param displayName 管理员展示名，可为空
 * @param password 管理员明文密码
 */
public record CreateTenantRequest(String slug, String name, String username, String displayName,
        String password) {

    /**
     * 转创建命令。
     *
     * @return 命令
     */
    public CreateTenantCommand toCommand() {
        return new CreateTenantCommand(slug, name, username, displayName, password);
    }
}
