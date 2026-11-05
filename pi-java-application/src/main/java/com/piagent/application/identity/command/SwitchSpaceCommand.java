package com.piagent.application.identity.command;

/**
 * 切换当前账号所在的空间。
 *
 * @param tenantSlug 目标空间短名
 */
public record SwitchSpaceCommand(String tenantSlug) {
}
