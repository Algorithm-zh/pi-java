package com.piagent.trigger.http.auth;

import com.piagent.application.identity.command.InviteMemberCommand;
import com.piagent.domain.identity.model.UserRole;

/**
 * 邀请已有账号加入空间。
 *
 * @param userId 账号标识
 * @param role 在该空间的角色
 */
public record InviteMemberRequest(String userId, UserRole role) {

    /**
     * 转邀请命令。
     *
     * @return 命令
     */
    public InviteMemberCommand toCommand() {
        return new InviteMemberCommand(userId, role);
    }
}
