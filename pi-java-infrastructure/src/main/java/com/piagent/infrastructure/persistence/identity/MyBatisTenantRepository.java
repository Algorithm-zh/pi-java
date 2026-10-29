package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.port.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的租户仓储。
 */
@Repository
public class MyBatisTenantRepository implements TenantRepository {

    private final TenantMapper mapper;
    private final TenantPoConverter converter = new TenantPoConverter();

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     */
    public MyBatisTenantRepository(TenantMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(converter::toDomain);
    }

    @Override
    public Optional<Tenant> findBySlug(String slug) {
        if (slug == null || slug.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findBySlug(slug.strip().toLowerCase(Locale.ROOT)))
                .map(converter::toDomain);
    }

    @Override
    public List<Tenant> findAll() {
        return mapper.findAll().stream().map(converter::toDomain).toList();
    }

    @Override
    public List<TenantId> findAllIds() {
        return mapper.findAllIds().stream().map(TenantId::parse).toList();
    }

    @Override
    public int updateDeletion(TenantId id, Instant deletedAt) {
        return mapper.updateDeletion(id.toString(), deletedAt);
    }

    @Override
    public void insert(Tenant tenant) {
        mapper.insert(converter.toPo(tenant));
    }
}
