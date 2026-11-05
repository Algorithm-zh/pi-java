package com.piagent.application.identity.service;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.SpaceMember;
import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.port.MembershipRepository;
import com.piagent.domain.identity.port.TenantRepository;
import com.piagent.domain.identity.port.UserRepository;
import java.util.ArrayList;
import java.util.List;

/**
 * 登录时的空间定位：把“用户名 + 可选空间短名”解析成候选空间。
 *
 * <p>账号是全局身份，空间来自成员关系。未指定空间时返回账号加入的全部未归档
 * 空间；指定空间时只返回该空间的成员关系。已归档空间不参与定位。</p>
 */
final class AccountLocator {

    /**
     * 账号在某个空间的登录候选。
     *
     * @param tenant 空间
     * @param membership 成员关系
     * @param user 账号
     */
    record Candidate(Tenant tenant, SpaceMember membership, AppUser user) {
    }

    private final TenantRepository tenants;
    private final MembershipRepository memberships;
    private final UserRepository users;

    /**
     * 创建定位器。
     *
     * @param tenants 空间仓储
     * @param memberships 成员仓储
     * @param users 账号仓储
     */
    AccountLocator(TenantRepository tenants, MembershipRepository memberships,
            UserRepository users) {
        this.tenants = tenants;
        this.memberships = memberships;
        this.users = users;
    }

    /**
     * 解析候选空间。
     *
     * @param username 已规范化的登录名
     * @param slug 空间短名；null 表示全部空间
     * @return 候选列表，按空间创建时间排序
     */
    List<Candidate> candidates(String username, String slug) {
        List<Candidate> found = new ArrayList<>();
        for (AppUser user : users.findByUsername(username)) {
            for (SpaceMember membership : memberships.findByUser(user.id())) {
                Tenant tenant = tenants.findById(membership.tenantId()).orElse(null);
                if (tenant == null || tenant.isArchived()) {
                    continue;
                }
                if (slug != null && !tenant.slug().equals(slug)) {
                    continue;
                }
                found.add(new Candidate(tenant, membership, user));
            }
        }
        found.sort((left, right) -> {
            int byTime = left.tenant().createdAt().compareTo(right.tenant().createdAt());
            return byTime != 0 ? byTime : left.tenant().id().toString()
                    .compareTo(right.tenant().id().toString());
        });
        return found;
    }
}
