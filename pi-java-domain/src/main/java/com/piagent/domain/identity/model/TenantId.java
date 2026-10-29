package com.piagent.domain.identity.model;

import com.piagent.domain.shared.IdGenerator;
import com.piagent.domain.shared.Ulid;
import java.util.Objects;

/**
 * 租户标识。
 *
 * <p>默认租户使用固定 ULID，升级前的会话与策略统一归入该租户，避免迁移时
 * 生成随机 ID 导致存量数据无法关联。</p>
 */
public record TenantId(Ulid value) {

    /** 默认租户标识，与 V6 迁移脚本中的固定值一致。 */
    public static final TenantId DEFAULT = new TenantId(Ulid.parse("00000000000000000000000001"));

    /**
     * 校验租户标识。
     *
     * @param value ULID
     */
    public TenantId {
        Objects.requireNonNull(value, "tenantId 不能为空");
    }

    /**
     * 解析字符串形式的租户标识。
     *
     * @param raw 26 位 ULID
     * @return 租户标识
     */
    public static TenantId parse(String raw) {
        return new TenantId(Ulid.parse(raw));
    }

    /**
     * 通过生成器创建新租户标识。
     *
     * @param generator 标识生成器
     * @return 新租户标识
     */
    public static TenantId newId(IdGenerator generator) {
        return new TenantId(Ulid.parse(generator.newId()));
    }

    /**
     * 是否默认租户。
     *
     * @return true 表示默认租户
     */
    public boolean isDefault() {
        return equals(DEFAULT);
    }

    @Override
    public String toString() {
        return value.value();
    }
}
