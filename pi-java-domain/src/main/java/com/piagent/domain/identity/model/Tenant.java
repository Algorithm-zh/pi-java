package com.piagent.domain.identity.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 租户（工作空间）。
 *
 * <p>租户是数据隔离的边界：会话、工具策略与附件都按租户过滤；模型档案、
 * 插件与技能属于进程级共享资源，不随租户变化。</p>
 *
 * @param id 租户标识
 * @param slug 登录用的稳定短名
 * @param name 展示名称
 * @param createdAt 创建时间
 */
public record Tenant(TenantId id, String slug, String name, Instant createdAt) {

    /** 默认租户短名。 */
    public static final String DEFAULT_SLUG = "default";

    /** 短名格式：小写字母或数字开头结尾，中间允许连字符。 */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?");

    /**
     * 校验租户。
     *
     * @param id 标识
     * @param slug 短名
     * @param name 名称
     * @param createdAt 创建时间
     */
    public Tenant {
        Objects.requireNonNull(id, "id 不能为空");
        slug = Guard.notBlank(slug, DomainErrorCode.INVALID_ARGUMENT, "租户短名不能为空");
        Guard.require(SLUG.matcher(slug).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "租户短名只能包含小写字母、数字与连字符，长度 2-32");
        name = Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "租户名称不能为空");
        Guard.maxLength(name, 128, DomainErrorCode.INVALID_ARGUMENT, "租户名称不能超过 128 个字符");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    /**
     * 创建新租户。
     *
     * @param id 标识
     * @param slug 短名
     * @param name 名称
     * @param now 创建时间
     * @return 新租户
     */
    public static Tenant create(TenantId id, String slug, String name, Instant now) {
        return new Tenant(id, slug.strip().toLowerCase(java.util.Locale.ROOT), name, now);
    }
}
