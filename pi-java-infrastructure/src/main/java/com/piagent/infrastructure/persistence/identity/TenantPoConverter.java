package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;

/**
 * `pi_tenant` 行与租户聚合之间的转换。
 */
public final class TenantPoConverter {

    /**
     * 聚合转持久化对象。
     *
     * @param tenant 租户
     * @return 持久化对象
     */
    public TenantPO toPo(Tenant tenant) {
        TenantPO po = new TenantPO();
        po.setId(tenant.id().toString());
        po.setSlug(tenant.slug());
        po.setName(tenant.name());
        po.setCreatedAt(tenant.createdAt());
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 租户
     */
    public Tenant toDomain(TenantPO po) {
        return new Tenant(TenantId.parse(po.getId()), po.getSlug(), po.getName(),
                po.getCreatedAt());
    }
}
