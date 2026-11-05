package com.piagent.application.identity.readmodel;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.UserRole;

/**
 * 空间成员摘要。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param role 在该空间的角色
 * @param status 账号状态
 */
public record UserSummary(String id, String username, String displayName, String role,
        String status) {

    /**
     * 由账号与成员角色构造。
     *
     * @param user 账号
     * @param role 在该空间的角色
     * @return 摘要
     */
    public static UserSummary of(AppUser user, UserRole role) {
        return new UserSummary(user.id().toString(), user.username(), user.displayName(),
                role.name(), user.status().name());
    }
}
