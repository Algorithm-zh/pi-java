package com.piagent.application.identity.readmodel;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.Tenant;

/**
 * 当前登录账号及其租户信息。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param role 角色
 * @param tenantId 租户标识
 * @param tenantSlug 租户短名
 * @param tenantName 租户名称
 */
public record AuthUserView(String id, String username, String displayName, String role,
        String tenantId, String tenantSlug, String tenantName) {

    /**
     * 由账号与租户构造。
     *
     * @param user 账号
     * @param tenant 租户
     * @return 读模型
     */
    public static AuthUserView of(AppUser user, Tenant tenant) {
        return new AuthUserView(user.id().toString(), user.username(), user.displayName(),
                user.role().name(), tenant.id().toString(), tenant.slug(), tenant.name());
    }
}
