package com.piagent.application.identity.service;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.port.TenantRepository;
import com.piagent.domain.identity.port.UserRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * 登录时的空间定位：把“用户名 + 可选空间短名”解析成候选账号。
 *
 * <p>未指定空间时跨全部未归档租户查找同名账号，因此普通用户不需要记住
 * 自己的空间短名；指定空间时只查该空间。已归档租户不参与任何定位。</p>
 */
final class AccountLocator {

    /**
     * 候选账号及其所属空间。
     *
     * @param tenant 空间
     * @param user 账号
     */
    record Candidate(Tenant tenant, AppUser user) {
    }

    private final TenantRepository tenants;
    private final UserRepository users;

    /**
     * 创建定位器。
     *
     * @param tenants 租户仓储
     * @param users 账号仓储
     */
    AccountLocator(TenantRepository tenants, UserRepository users) {
        this.tenants = tenants;
        this.users = users;
    }

    /**
     * 解析候选账号。
     *
     * @param username 已规范化的登录名
     * @param slug 空间短名；null 表示自动定位
     * @return 候选列表，按空间创建时间排序
     */
    List<Candidate> candidates(String username, String slug) {
        if (slug != null) {
            Tenant tenant = tenants.findBySlug(slug).orElse(null);
            if (tenant == null || tenant.isArchived()) {
                return List.of();
            }
            return users.findByUsername(tenant.id(), username)
                    .map(user -> List.of(new Candidate(tenant, user)))
                    .orElse(List.of());
        }
        List<Candidate> found = new ArrayList<>();
        for (AppUser user : users.findActiveByUsername(username)) {
            tenants.findById(user.tenantId())
                    .filter(tenant -> !tenant.isArchived())
                    .ifPresent(tenant -> found.add(new Candidate(tenant, user)));
        }
        return found;
    }
}
