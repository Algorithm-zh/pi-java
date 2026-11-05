package com.piagent.application.identity.command;

import com.piagent.domain.identity.model.UserRole;

/**
 * 把已有账号邀请进当前空间。
 *
 * @param userId 账号标识
 * @param role 在该空间的角色
 */
public record InviteMemberCommand(String userId, UserRole role) {
}
