package com.piagent.application.identity.command;

/**
 * 创建团队空间；创建者自动成为管理员。
 *
 * @param slug 空间短名
 * @param name 空间名称
 */
public record CreateTenantCommand(String slug, String name) {
}
