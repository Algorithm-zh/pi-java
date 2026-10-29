package com.piagent.application.identity.readmodel;

import com.piagent.domain.identity.model.Tenant;
import java.time.Instant;

/**
 * 租户摘要。
 *
 * @param id 租户标识
 * @param slug 短名
 * @param name 名称
 * @param deletedAt 归档时间；null 表示正常
 */
public record TenantSummary(String id, String slug, String name, Instant deletedAt) {

    /**
     * 由租户构造。
     *
     * @param tenant 租户
     * @return 摘要
     */
    public static TenantSummary of(Tenant tenant) {
        return new TenantSummary(tenant.id().toString(), tenant.slug(), tenant.name(),
                tenant.deletedAt());
    }
}
