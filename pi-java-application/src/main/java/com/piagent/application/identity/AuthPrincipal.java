package com.piagent.application.identity;

import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 当前请求的认证主体。
 *
 * <p>系统任务没有具体账号，使用 {@link #system(TenantId)} 构造；用户请求必须携带
 * 账号标识。租户来自令牌，不信任客户端传入的任何租户参数。</p>
 *
 * @param tenantId 租户
 * @param userId 账号；系统任务为 null
 * @param username 登录名
 * @param displayName 展示名，用于审批审计
 * @param role 角色
 */
public record AuthPrincipal(
        TenantId tenantId,
        UserId userId,
        String username,
        String displayName,
        UserRole role) {

    /**
     * 校验主体。
     *
     * @param tenantId 租户
     * @param userId 账号
     * @param username 登录名
     * @param displayName 展示名
     * @param role 角色
     */
    public AuthPrincipal {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        username = Guard.notBlank(username, DomainErrorCode.INVALID_ARGUMENT, "用户名不能为空");
        displayName = Guard.notBlank(displayName, DomainErrorCode.INVALID_ARGUMENT,
                "展示名不能为空");
        Objects.requireNonNull(role, "role 不能为空");
    }

    /**
     * 系统任务主体。
     *
     * @param tenantId 租户
     * @return 系统主体
     */
    public static AuthPrincipal system(TenantId tenantId) {
        return new AuthPrincipal(tenantId, null, "system", "系统任务", UserRole.ADMIN);
    }

    /**
     * 是否系统任务。
     *
     * @return true 表示没有具体账号
     */
    public boolean isSystem() {
        return userId == null;
    }

    /**
     * 是否管理员。
     *
     * @return true 表示管理员
     */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
