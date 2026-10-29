package com.piagent.infrastructure.persistence.identity;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * `pi_app_user` 表 MyBatis 映射器。
 */
@Mapper
public interface UserMapper {

    /**
     * 插入账号。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_app_user
                (id, tenant_id, username, display_name, password_hash, role, status, created_at, updated_at)
            VALUES
                (#{id}, #{tenantId}, #{username}, #{displayName}, #{passwordHash}, #{role}, #{status},
                 #{createdAt}, #{updatedAt})
            """)
    int insert(UserPO po);

    /**
     * 按标识查询。
     *
     * @param id 账号标识
     * @return 持久化对象
     */
    @Select("SELECT * FROM pi_app_user WHERE id = #{id}")
    UserPO findById(@Param("id") String id);

    /**
     * 在租户内按登录名查询。
     *
     * @param tenantId 租户标识
     * @param username 已规范化的登录名
     * @return 持久化对象
     */
    @Select("""
            SELECT * FROM pi_app_user
            WHERE tenant_id = #{tenantId} AND username = #{username}
            """)
    UserPO findByUsername(@Param("tenantId") String tenantId, @Param("username") String username);

    /**
     * 跨租户按登录名查询，只返回未归档租户中的账号。
     *
     * @param username 已规范化的登录名
     * @return 按租户创建时间升序
     */
    @Select("""
            SELECT u.* FROM pi_app_user u
            JOIN pi_tenant t ON t.id = u.tenant_id
            WHERE u.username = #{username} AND t.deleted_at IS NULL
            ORDER BY t.created_at ASC, u.id ASC
            """)
    List<UserPO> findActiveByUsername(@Param("username") String username);

    /**
     * 查询租户下的全部账号。
     *
     * @param tenantId 租户标识
     * @return 按创建时间升序
     */
    @Select("""
            SELECT * FROM pi_app_user
            WHERE tenant_id = #{tenantId}
            ORDER BY created_at ASC, id ASC
            """)
    List<UserPO> findByTenant(@Param("tenantId") String tenantId);

    /**
     * 账号总数。
     *
     * @return 总数
     */
    @Select("SELECT COUNT(*) FROM pi_app_user")
    long count();
}
