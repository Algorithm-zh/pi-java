package com.piagent.application.identity.readmodel;

import com.piagent.domain.identity.model.AppUser;

/**
 * 账号摘要。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param role 角色
 * @param status 状态
 */
public record UserSummary(String id, String username, String displayName, String role,
        String status) {

    /**
     * 由账号构造。
     *
     * @param user 账号
     * @return 摘要
     */
    public static UserSummary of(AppUser user) {
        return new UserSummary(user.id().toString(), user.username(), user.displayName(),
                user.role().name(), user.status().name());
    }
}
