package com.piagent.domain.identity.port;

import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 租户仓储。
 *
 * <p>归档（软删除）只写 {@code deleted_at}，数据保留；因此按标识查询会返回
 * 归档租户，而按短名登录与后台任务只看未归档租户。</p>
 */
public interface TenantRepository {

    /**
     * 按标识查询，包含已归档租户。
     *
     * @param id 租户标识
     * @return 租户，不存在时为空
     */
    Optional<Tenant> findById(TenantId id);

    /**
     * 按短名查询，包含已归档租户（短名全局唯一，归档后仍占用）。
     *
     * @param slug 短名
     * @return 租户，不存在时为空
     */
    Optional<Tenant> findBySlug(String slug);

    /**
     * 查询全部租户，包含已归档租户。
     *
     * @return 租户列表
     */
    List<Tenant> findAll();

    /**
     * 查询未归档租户标识，供后台任务逐租户执行。
     *
     * @return 租户标识列表
     */
    List<TenantId> findAllIds();

    /**
     * 写入归档时间。
     *
     * @param id 租户标识
     * @param deletedAt 归档时间；null 表示恢复
     * @return 影响行数
     */
    int updateDeletion(TenantId id, Instant deletedAt);

    /**
     * 插入新租户。
     *
     * @param tenant 租户
     */
    void insert(Tenant tenant);
}
