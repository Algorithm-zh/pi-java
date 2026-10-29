package com.piagent.application.testsupport;

import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.port.TenantRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用层测试用的内存租户仓储。
 */
public final class InMemoryTenantRepository implements TenantRepository {

    private final List<Tenant> tenants = new ArrayList<>();

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return tenants.stream().filter(tenant -> tenant.id().equals(id)).findFirst();
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        return tenants.stream().filter(tenant -> tenant.slug().equals(slug)).findFirst();
    }

    @Override
    public List<Tenant> findAll() {
        return List.copyOf(tenants);
    }

    @Override
    public List<TenantId> findAllIds() {
        return tenants.stream().map(Tenant::id).toList();
    }

    @Override
    public void insert(Tenant tenant) {
        tenants.add(tenant);
    }
}
