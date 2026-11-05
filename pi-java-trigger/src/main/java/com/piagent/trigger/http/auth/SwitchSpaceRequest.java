package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.SwitchSpaceCommand;

/**
 * 切换空间请求。
 *
 * @param tenantSlug 目标空间短名
 */
public record SwitchSpaceRequest(String tenantSlug) {

    /**
     * 转切换命令。
     *
     * @return 命令
     */
    public SwitchSpaceCommand toCommand() {
        return new SwitchSpaceCommand(tenantSlug);
    }
}
