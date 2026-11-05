package com.piagent.domain.identity.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 空间（工作空间）。
 *
 * <p>空间是数据隔离的边界：会话、工具策略与附件都按空间过滤；模型档案、
 * 插件与技能属于进程级共享资源。账号通过 {@link SpaceMember} 加入空间，
 * 同一个账号可以同时属于个人空间与多个团队空间。</p>
 *
 * <p>归档（软删除）只记录时间，不删除任何数据；归档后不再参与登录定位，
 * 可随时恢复。</p>
 *
 * @param id 空间标识
 * @param slug 稳定短名
 * @param name 展示名称
 * @param kind 空间类型
 * @param createdAt 创建时间
 * @param deletedAt 归档时间；null 表示正常
 */
public record Tenant(TenantId id, String slug, String name, TenantKind kind, Instant createdAt,
        Instant deletedAt) {

    /** 默认空间短名。 */
    public static final String DEFAULT_SLUG = "default";

    /** 短名格式：小写字母或数字开头结尾，中间允许连字符。 */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,30}[a-z0-9])?");

    /**
     * 校验空间。
     *
     * @param id 标识
     * @param slug 短名
     * @param name 名称
     * @param kind 类型
     * @param createdAt 创建时间
     * @param deletedAt 归档时间
     */
    public Tenant {
        Objects.requireNonNull(id, "id 不能为空");
        slug = Guard.notBlank(slug, DomainErrorCode.INVALID_ARGUMENT, "空间短名不能为空");
        Guard.require(SLUG.matcher(slug).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "空间短名只能包含小写字母、数字与连字符，长度 2-32");
        name = Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "空间名称不能为空");
        Guard.maxLength(name, 128, DomainErrorCode.INVALID_ARGUMENT, "空间名称不能超过 128 个字符");
        Objects.requireNonNull(kind, "kind 不能为空");
        Objects.requireNonNull(createdAt, "createdAt 不能为空");
    }

    /**
     * 创建新空间。
     *
     * @param id 标识
     * @param slug 短名
     * @param name 名称
     * @param kind 类型
     * @param now 创建时间
     * @return 新空间
     */
    public static Tenant create(TenantId id, String slug, String name, TenantKind kind,
            Instant now) {
        return new Tenant(id, slug.strip().toLowerCase(Locale.ROOT), name, kind, now, null);
    }

    /**
     * 是否默认空间。
     *
     * @return true 表示默认空间
     */
    public boolean isDefault() {
        return kind == TenantKind.DEFAULT;
    }

    /**
     * 是否个人空间。
     *
     * @return true 表示个人空间
     */
    public boolean isPersonal() {
        return kind == TenantKind.PERSONAL;
    }

    /**
     * 是否已归档。
     *
     * @return true 表示已被软删除
     */
    public boolean isArchived() {
        return deletedAt != null;
    }

    /**
     * 归档空间；已归档时幂等返回自身。
     *
     * @param now 归档时间
     * @return 归档后的空间
     */
    public Tenant archive(Instant now) {
        Objects.requireNonNull(now, "now 不能为空");
        return isArchived() ? this : new Tenant(id, slug, name, kind, createdAt, now);
    }

    /**
     * 恢复归档空间；未归档时幂等返回自身。
     *
     * @return 恢复后的空间
     */
    public Tenant restore() {
        return isArchived() ? new Tenant(id, slug, name, kind, createdAt, null) : this;
    }
}
