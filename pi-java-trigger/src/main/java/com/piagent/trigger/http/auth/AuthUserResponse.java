package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.AuthUserView;

/**
 * 账号与空间信息响应。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param role 在当前空间的角色
 * @param tenantId 空间标识
 * @param tenantSlug 空间短名
 * @param tenantName 空间名称
 * @param tenantKind 空间类型
 */
public record AuthUserResponse(String id, String username, String displayName, String role,
        String tenantId, String tenantSlug, String tenantName, String tenantKind) {

    /**
     * 由读模型转换。
     *
     * @param view 账号读模型
     * @return 响应
     */
    public static AuthUserResponse from(AuthUserView view) {
        return new AuthUserResponse(view.id(), view.username(), view.displayName(), view.role(),
                view.tenantId(), view.tenantSlug(), view.tenantName(), view.tenantKind());
    }
}
