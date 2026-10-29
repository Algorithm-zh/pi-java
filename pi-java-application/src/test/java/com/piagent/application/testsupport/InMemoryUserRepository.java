package com.piagent.application.testsupport;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.port.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * 应用层测试用的内存账号仓储。
 *
 * <p>{@link #findActiveByUsername(String)} 需要知道空间是否已归档；绑定租户仓储时
 * 按归档状态过滤，未绑定时视为全部空间可用。</p>
 */
public final class InMemoryUserRepository implements UserRepository {

    private final List<AppUser> users = new ArrayList<>();
    private final Predicate<TenantId> tenantActive;

    /**
     * 不感知租户状态的仓储。
     */
    public InMemoryUserRepository() {
        this(id -> true);
    }

    /**
     * 读取租户归档状态的仓储。
     *
     * @param tenants 内存租户仓储
     */
    public InMemoryUserRepository(InMemoryTenantRepository tenants) {
        this(id -> tenants.findById(id)
                .map(tenant -> !tenant.isArchived())
                .orElse(false));
    }

    private InMemoryUserRepository(Predicate<TenantId> tenantActive) {
        this.tenantActive = tenantActive;
    }

    @Override
    public Optional<AppUser> findById(UserId id) {
        return users.stream().filter(user -> user.id().equals(id)).findFirst();
    }

    @Override
    public Optional<AppUser> findByUsername(TenantId tenantId, String username) {
        return users.stream()
                .filter(user -> user.tenantId().equals(tenantId))
                .filter(user -> user.username().equals(username))
                .findFirst();
    }

    @Override
    public List<AppUser> findActiveByUsername(String username) {
        return users.stream()
                .filter(user -> user.username().equals(username))
                .filter(user -> tenantActive.test(user.tenantId()))
                .toList();
    }

    @Override
    public List<AppUser> findByTenant(TenantId tenantId) {
        return users.stream().filter(user -> user.tenantId().equals(tenantId)).toList();
    }

    @Override
    public long count() {
        return users.size();
    }

    @Override
    public void insert(AppUser user) {
        users.removeIf(existing -> existing.id().equals(user.id()));
        users.add(user);
    }
}
