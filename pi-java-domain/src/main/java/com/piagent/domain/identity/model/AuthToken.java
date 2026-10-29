package com.piagent.domain.identity.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 访问令牌记录。
 *
 * <p>明文令牌只在下发时返回一次；本记录只保存 SHA-256 十六进制哈希。
 * 令牌绑定账号与租户，登出或过期后不可再用。</p>
 *
 * @param id 令牌标识
 * @param tenantId 租户
 * @param userId 账号
 * @param tokenHash 明文令牌的 SHA-256 哈希
 * @param issuedAt 签发时间
 * @param expiresAt 过期时间
 * @param lastUsedAt 最近使用时间；从未使用为 null
 * @param revokedAt 吊销时间；未吊销为 null
 */
public record AuthToken(
        AuthTokenId id,
        TenantId tenantId,
        UserId userId,
        String tokenHash,
        Instant issuedAt,
        Instant expiresAt,
        Instant lastUsedAt,
        Instant revokedAt) {

    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

    /**
     * 校验令牌记录。
     *
     * @param id 标识
     * @param tenantId 租户
     * @param userId 账号
     * @param tokenHash 哈希
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @param lastUsedAt 最近使用时间
     * @param revokedAt 吊销时间
     */
    public AuthToken {
        Objects.requireNonNull(id, "id 不能为空");
        Objects.requireNonNull(tenantId, "tenantId 不能为空");
        Objects.requireNonNull(userId, "userId 不能为空");
        tokenHash = Guard.notBlank(tokenHash, DomainErrorCode.INVALID_ARGUMENT,
                "令牌哈希不能为空");
        Guard.require(HASH.matcher(tokenHash).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "令牌哈希必须是 64 位小写十六进制");
        Objects.requireNonNull(issuedAt, "issuedAt 不能为空");
        Objects.requireNonNull(expiresAt, "expiresAt 不能为空");
        Guard.require(expiresAt.isAfter(issuedAt), DomainErrorCode.INVALID_ARGUMENT,
                "令牌过期时间必须晚于签发时间");
    }

    /**
     * 签发新令牌。
     *
     * @param id 标识
     * @param tenantId 租户
     * @param userId 账号
     * @param tokenHash 哈希
     * @param issuedAt 签发时间
     * @param expiresAt 过期时间
     * @return 新令牌记录
     */
    public static AuthToken issue(AuthTokenId id, TenantId tenantId, UserId userId,
            String tokenHash, Instant issuedAt, Instant expiresAt) {
        return new AuthToken(id, tenantId, userId, tokenHash, issuedAt, expiresAt, null, null);
    }

    /**
     * 在指定时间是否可用。
     *
     * @param now 当前时间
     * @return true 表示未吊销且未过期
     */
    public boolean isUsableAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /**
     * 吊销令牌。
     *
     * @param now 吊销时间
     * @return 新记录
     */
    public AuthToken revoke(Instant now) {
        if (revokedAt != null) {
            return this;
        }
        return new AuthToken(id, tenantId, userId, tokenHash, issuedAt, expiresAt, lastUsedAt, now);
    }

    /**
     * 记录使用时间。
     *
     * @param now 使用时间
     * @return 新记录
     */
    public AuthToken touch(Instant now) {
        return new AuthToken(id, tenantId, userId, tokenHash, issuedAt, expiresAt, now, revokedAt);
    }
}
