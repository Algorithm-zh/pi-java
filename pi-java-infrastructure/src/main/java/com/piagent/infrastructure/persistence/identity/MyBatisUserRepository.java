package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.port.UserRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的账号仓储。
 */
@Repository
public class MyBatisUserRepository implements UserRepository {

    private final UserMapper mapper;
    private final UserPoConverter converter = new UserPoConverter();

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     */
    public MyBatisUserRepository(UserMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<AppUser> findById(UserId id) {
        return Optional.ofNullable(mapper.findById(id.toString())).map(converter::toDomain);
    }

    @Override
    public Optional<AppUser> findByUsername(TenantId tenantId, String username) {
        String normalized = AppUser.normalizeUsername(username);
        if (normalized.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findByUsername(tenantId.toString(), normalized))
                .map(converter::toDomain);
    }

    @Override
    public List<AppUser> findActiveByUsername(String username) {
        String normalized = AppUser.normalizeUsername(username);
        if (normalized.isEmpty()) {
            return List.of();
        }
        return mapper.findActiveByUsername(normalized).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public List<AppUser> findByTenant(TenantId tenantId) {
        return mapper.findByTenant(tenantId.toString()).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public long count() {
        return mapper.count();
    }

    @Override
    public void insert(AppUser user) {
        mapper.insert(converter.toPo(user));
    }
}
