package com.piagent.domain.identity.port;

import com.piagent.domain.identity.model.SpaceMember;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import java.util.List;
import java.util.Optional;

/**
 * 空间成员关系仓储。
 */
public interface MembershipRepository {

    /**
     * 查询账号在指定空间的成员关系。
     *
     * @param tenantId 空间
     * @param userId 账号
     * @return 成员关系，不存在时为空
     */
    Optional<SpaceMember> find(TenantId tenantId, UserId userId);

    /**
     * 查询空间的全部成员。
     *
     * @param tenantId 空间
     * @return 成员关系列表
     */
    List<SpaceMember> findByTenant(TenantId tenantId);

    /**
     * 查询账号加入的全部空间。
     *
     * @param userId 账号
     * @return 成员关系列表
     */
    List<SpaceMember> findByUser(UserId userId);

    /**
     * 新增成员关系；已存在时由数据库唯一约束拒绝。
     *
     * @param member 成员关系
     */
    void insert(SpaceMember member);

    /**
     * 移除成员关系。
     *
     * @param tenantId 空间
     * @param userId 账号
     * @return 影响行数
     */
    int delete(TenantId tenantId, UserId userId);

    /**
     * 统计空间管理员数量。
     *
     * @param tenantId 空间
     * @return 管理员数量
     */
    long countAdmins(TenantId tenantId);
}
