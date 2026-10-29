package com.piagent.domain.identity.port;

import com.piagent.domain.identity.model.Tenant;
import com.piagent.domain.identity.model.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * 租户仓储。
 */
public interface TenantRepository {

    /**
     * 按标识查询。
     *
     * @param id 租户标识
     * @return 租户，不存在时为空
     */
    Optional<Tenant> findById(TenantId id);

    /**
     * 按短名查询。
     *
     * @param slug 短名
     * @return 租户，不存在时为空
     */
    Optional<Tenant> findBySlug(String slug);

    /**
     * 查询全部租户。
     *
     * @return 租户列表
     */
    List<Tenant> findAll();

    /**
     * 查询全部租户标识，供后台任务逐租户执行。
     *
     * @return 租户标识列表
     */
    List<TenantId> findAllIds();

    /**
     * 插入新租户。
     *
     * @param tenant 租户
     */
    void insert(Tenant tenant);
}
