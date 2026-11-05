package com.piagent.domain.identity.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 空间成员关系。
 *
 * <p>账号是全局身份，空间成员关系决定账号能进入哪些空间以及在该空间的角色。
 * 同一个账号可以在个人空间是管理员、在团队空间是普通成员。</p>
 *
 * @param tenantId 空间
 * @param userId 账号
 * @param role 在该空间的角色
 * @param createdAt 加入时间
 */
public record SpaceMember(TenantId tenantId, UserId userId, UserRole role, Instant createdAt) {

    /**
     * 校验成员关系。
     *
     * @param tenantId 空间
     * @param userId 账号
     * @param role 角色
     * @param createdAt 加入时间
     */
    public SpaceMember {
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        Objects.requireNonNull(role, "role 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    /**
     * 创建成员关系。
     *
     * @param tenantId 空间
     * @param userId 账号
     * @param role 角色
     * @param now 加入时间
     * @return 成员关系
     */
    public static SpaceMember create(TenantId tenantId, UserId userId, UserRole role,
            Instant now) {
        return new SpaceMember(tenantId, userId, role, now);
    }

    /**
     * 是否空间管理员。
     *
     * @return true 表示管理员
     */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }
}
