package com.piagent.application.identity.readmodel;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.UserRole;

/**
 * 当前登录账号及其空间信息。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param role 在该空间的角色
 * @param tenantId 空间标识
 * @param tenantSlug 空间短名
 * @param tenantName 空间名称
 * @param tenantKind 空间类型
 */
public record AuthUserView(String id, String username, String displayName, String role,
        String tenantId, String tenantSlug, String tenantName, String tenantKind) {

    /**
     * 由账号、空间与成员角色构造。
     *
     * @param user 账号
     * @param tenant 空间
     * @param role 成员角色
     * @return 读模型
     */
    public static AuthUserView of(AppUser user, Tenant tenant, UserRole role) {
        return new AuthUserView(user.id().toString(), user.username(), user.displayName(),
                role.name(), tenant.id().toString(), tenant.slug(), tenant.name(),
                tenant.kind().name());
    }
}
