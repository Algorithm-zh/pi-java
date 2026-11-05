package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.TenantKind;

/**
 * `pi_tenant` 行与空间聚合之间的转换。
 */
public final class TenantPoConverter {

    /**
     * 聚合转持久化对象。
     *
     * @param tenant 空间
     * @return 持久化对象
     */
    public TenantPO toPo(Tenant tenant) {
        TenantPO po = new TenantPO();
        po.setId(tenant.id().toString());
        po.setSlug(tenant.slug());
        po.setName(tenant.name());
        po.setKind(tenant.kind().name());
        po.setCreatedAt(tenant.createdAt());
        po.setDeletedAt(tenant.deletedAt());
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 空间
     */
    public Tenant toDomain(TenantPO po) {
        return new Tenant(TenantId.parse(po.getId()), po.getSlug(), po.getName(),
                TenantKind.valueOf(po.getKind()), po.getCreatedAt(), po.getDeletedAt());
    }
}
