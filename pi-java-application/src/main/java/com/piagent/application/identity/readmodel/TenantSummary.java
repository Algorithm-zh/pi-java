package com.piagent.application.identity.readmodel;

import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.UserRole;
import java.time.Instant;

/**
 * 空间摘要，包含当前账号在该空间的角色。
 *
 * @param id 空间标识
 * @param slug 短名
 * @param name 名称
 * @param kind 空间类型
 * @param role 当前账号在该空间的角色
 * @param deletedAt 归档时间；null 表示正常
 */
public record TenantSummary(String id, String slug, String name, String kind, String role,
        Instant deletedAt) {

    /**
     * 由空间与成员角色构造。
     *
     * @param tenant 空间
     * @param role 当前账号的角色
     * @return 摘要
     */
    public static TenantSummary of(Tenant tenant, UserRole role) {
        return new TenantSummary(tenant.id().toString(), tenant.slug(), tenant.name(),
                tenant.kind().name(), role.name(), tenant.deletedAt());
    }
}
