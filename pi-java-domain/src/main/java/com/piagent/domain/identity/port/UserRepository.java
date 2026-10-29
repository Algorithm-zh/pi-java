package com.piagent.domain.identity.port;

import com.piagent.domain.identity.model.AppUser;
import com.piagent.domain.identity.model.TenantId;
import com.piagent.domain.identity.model.UserId;
import java.util.List;
import java.util.Optional;

/**
 * 本地账号仓储。
 */
public interface UserRepository {

    /**
     * 按标识查询。
     *
     * @param id 账号标识
     * @return 账号，不存在时为空
     */
    Optional<AppUser> findById(UserId id);

    /**
     * 在租户内按登录名查询。
     *
     * @param tenantId 租户
     * @param username 已规范化的登录名
     * @return 账号，不存在时为空
     */
    Optional<AppUser> findByUsername(TenantId tenantId, String username);

    /**
     * 跨租户按登录名查询，只返回未归档租户中的账号，供登录时自动定位空间。
     *
     * @param username 已规范化的登录名
     * @return 账号列表，多个空间使用同一登录名时会返回多条
     */
    List<AppUser> findActiveByUsername(String username);

    /**
     * 查询租户下的全部账号。
     *
     * @param tenantId 租户
     * @return 账号列表
     */
    List<AppUser> findByTenant(TenantId tenantId);

    /**
     * 账号总数，用于判断是否需要首次初始化。
     *
     * @return 总数
     */
    long count();

    /**
     * 插入新账号。
     *
     * @param user 账号
     */
    void insert(AppUser user);
}
