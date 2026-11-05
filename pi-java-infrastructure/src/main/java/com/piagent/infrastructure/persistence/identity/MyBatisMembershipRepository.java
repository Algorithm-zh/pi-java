package com.piagent.infrastructure.persistence.identity;

import com.piagent.domain.identity.model.SpaceMember;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import com.piagent.domain.identity.model.UserRole;
import com.piagent.domain.identity.port.MembershipRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的空间成员仓储。
 */
@Repository
public class MyBatisMembershipRepository implements MembershipRepository {

    private final SpaceMemberMapper mapper;

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     */
    public MyBatisMembershipRepository(SpaceMemberMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<SpaceMember> find(TenantId tenantId, UserId userId) {
        return Optional.ofNullable(mapper.find(tenantId.toString(), userId.toString()))
                .map(this::toDomain);
    }

    @Override
    public List<SpaceMember> findByTenant(TenantId tenantId) {
        return mapper.findByTenant(tenantId.toString()).stream().map(this::toDomain).toList();
    }

    @Override
    public List<SpaceMember> findByUser(UserId userId) {
        return mapper.findByUser(userId.toString()).stream().map(this::toDomain).toList();
    }

    @Override
    public void insert(SpaceMember member) {
        SpaceMemberPO po = new SpaceMemberPO();
        po.setTenantId(member.tenantId().toString());
        po.setUserId(member.userId().toString());
        po.setRole(member.role().name());
        po.setCreatedAt(member.createdAt());
        mapper.insert(po);
    }

    @Override
    public int delete(TenantId tenantId, UserId userId) {
        return mapper.delete(tenantId.toString(), userId.toString());
    }

    @Override
    public long countAdmins(TenantId tenantId) {
        return mapper.countAdmins(tenantId.toString());
    }

    private SpaceMember toDomain(SpaceMemberPO po) {
        return new SpaceMember(TenantId.parse(po.getTenantId()), UserId.parse(po.getUserId()),
                UserRole.valueOf(po.getRole()), po.getCreatedAt());
    }
}
