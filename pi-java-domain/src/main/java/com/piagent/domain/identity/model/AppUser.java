package com.piagent.domain.identity.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 本地账号。
 *
 * <p>密码只以 PBKDF2 编码保存；用户名在租户内唯一，跨租户可以重名。</p>
 *
 * @param id 账号标识
 * @param tenantId 所属租户
 * @param username 登录名
 * @param displayName 展示名
 * @param passwordHash 密码哈希
 * @param role 角色
 * @param status 状态
 * @param createdAt 创建时间
 * @param updatedAt 更新时间
 */
public record AppUser(
        UserId id,
        TenantId tenantId,
        String username,
        String displayName,
        String passwordHash,
        UserRole role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt) {

    /** 用户名最大长度。 */
    public static final int MAX_USERNAME_LENGTH = 64;

    /** 用户名最小长度。 */
    public static final int MIN_USERNAME_LENGTH = 3;

    private static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9._-]+");

    /**
     * 校验账号。
     *
     * @param id 标识
     * @param tenantId 租户
     * @param username 登录名
     * @param displayName 展示名
     * @param passwordHash 密码哈希
     * @param role 角色
     * @param status 状态
     * @param createdAt 创建时间
     * @param updatedAt 更新时间
     */
    public AppUser {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        username = Guard.notBlank(username, DomainErrorCode.INVALID_ARGUMENT, "用户名不能为空");
        Guard.require(username.length() >= MIN_USERNAME_LENGTH
                        && username.length() <= MAX_USERNAME_LENGTH,
                DomainErrorCode.INVALID_ARGUMENT, "用户名长度必须在 3 到 64 之间");
        Guard.require(USERNAME.matcher(username).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "用户名只能包含字母、数字、点、下划线与连字符");
        username = username.toLowerCase(Locale.ROOT);
        displayName = displayName == null || displayName.isBlank() ? username : displayName.strip();
        Guard.maxLength(displayName, 128, DomainErrorCode.INVALID_ARGUMENT,
                "展示名不能超过 128 个字符");
        passwordHash = Guard.notBlank(passwordHash, DomainErrorCode.INVALID_ARGUMENT,
                "密码哈希不能为空");
        Guard.maxLength(passwordHash, 255, DomainErrorCode.INVALID_ARGUMENT,
                "密码哈希不能超过 255 个字符");
        Objects.requireNonNull(role, "role 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
        Objects.requireNonNull(updatedAt, "updatedAt 不能为空");
    }

    /**
     * 创建启用状态的新账号。
     *
     * @param id 标识
     * @param tenantId 租户
     * @param username 登录名
     * @param displayName 展示名；空白时使用登录名
     * @param passwordHash 密码哈希
     * @param role 角色
     * @param now 创建时间
     * @return 新账号
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public static AppUser create(UserId id, TenantId tenantId, String username, String displayName,
            String passwordHash, UserRole role, Instant now) {
        return new AppUser(id, tenantId, username.strip(), displayName, passwordHash, role,
                UserStatus.ACTIVE, now, now);
    }

    /**
     * 是否管理员。
     *
     * @return true 表示管理员
     */
    public boolean isAdmin() {
        return role == UserRole.ADMIN;
    }

    /**
     * 是否可登录。
     *
     * @return true 表示启用中
     */
    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }

    /**
     * 规范化登录名，用于查询。
     *
     * @param raw 原始输入
     * @return 去空白并保留大小写的登录名
     */
    public static String normalizeUsername(String raw) {
        return raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
    }
}
