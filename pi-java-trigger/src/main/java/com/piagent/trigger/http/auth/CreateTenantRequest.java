package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.CreateTenantCommand;

/**
 * 创建团队空间请求；创建者自动成为该空间管理员。
 *
 * @param slug 空间短名
 * @param name 空间名称
 */
public record CreateTenantRequest(String slug, String name) {

    /**
     * 转创建命令。
     *
     * @return 命令
     */
    public CreateTenantCommand toCommand() {
        return new CreateTenantCommand(slug, name);
    }
}
