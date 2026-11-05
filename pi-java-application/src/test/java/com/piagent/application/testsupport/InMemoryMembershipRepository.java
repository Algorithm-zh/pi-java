package com.piagent.application.testsupport;

import com.piagent.domain.identity.model.SpaceMember;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.port.MembershipRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用层测试用的内存成员关系仓储。
 */
public final class InMemoryMembershipRepository implements MembershipRepository {

    private final List<SpaceMember> members = new ArrayList<>();

    @Override
    public Optional<SpaceMember> find(TenantId tenantId, UserId userId) {
        return members.stream()
                .filter(member -> member.tenantId().equals(tenantId))
                .filter(member -> member.userId().equals(userId))
                .findFirst();
    }

    @Override
    public List<SpaceMember> findByTenant(TenantId tenantId) {
        return members.stream().filter(member -> member.tenantId().equals(tenantId)).toList();
    }

    @Override
    public List<SpaceMember> findByUser(UserId userId) {
        return members.stream().filter(member -> member.userId().equals(userId)).toList();
    }

    @Override
    public void insert(SpaceMember member) {
        if (find(member.tenantId(), member.userId()).isPresent()) {
            throw new IllegalStateException("成员关系已存在");
        }
        members.add(member);
    }

    @Override
    public int delete(TenantId tenantId, UserId userId) {
        return members.removeIf(member -> member.tenantId().equals(tenantId)
                && member.userId().equals(userId)) ? 1 : 0;
    }

    @Override
    public long countAdmins(TenantId tenantId) {
        return members.stream()
                .filter(member -> member.tenantId().equals(tenantId))
                .filter(SpaceMember::isAdmin)
                .count();
    }
}
