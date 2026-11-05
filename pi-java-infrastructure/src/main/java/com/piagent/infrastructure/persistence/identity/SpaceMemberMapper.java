package com.piagent.infrastructure.persistence.identity;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * `pi_space_member` 表 MyBatis 映射器。
 */
@Mapper
public interface SpaceMemberMapper {

    /**
     * 插入成员关系。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_space_member (tenant_id, user_id, role, created_at)
            VALUES (#{tenantId}, #{userId}, #{role}, #{createdAt})
            """)
    int insert(SpaceMemberPO po);

    /**
     * 查询成员关系。
     *
     * @param tenantId 空间标识
     * @param userId 账号标识
     * @return 持久化对象
     */
    @Select("""
            SELECT * FROM pi_space_member
            WHERE tenant_id = #{tenantId} AND user_id = #{userId}
            """)
    SpaceMemberPO find(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /**
     * 查询空间的全部成员。
     *
     * @param tenantId 空间标识
     * @return 按加入时间升序
     */
    @Select("""
            SELECT * FROM pi_space_member
            WHERE tenant_id = #{tenantId}
            ORDER BY created_at ASC, user_id ASC
            """)
    List<SpaceMemberPO> findByTenant(@Param("tenantId") String tenantId);

    /**
     * 查询账号加入的全部空间。
     *
     * @param userId 账号标识
     * @return 按加入时间升序
     */
    @Select("""
            SELECT * FROM pi_space_member
            WHERE user_id = #{userId}
            ORDER BY created_at ASC, tenant_id ASC
            """)
    List<SpaceMemberPO> findByUser(@Param("userId") String userId);

    /**
     * 删除成员关系。
     *
     * @param tenantId 空间标识
     * @param userId 账号标识
     * @return 影响行数
     */
    @Delete("""
            DELETE FROM pi_space_member
            WHERE tenant_id = #{tenantId} AND user_id = #{userId}
            """)
    int delete(@Param("tenantId") String tenantId, @Param("userId") String userId);

    /**
     * 统计空间管理员数量。
     *
     * @param tenantId 空间标识
     * @return 管理员数量
     */
    @Select("""
            SELECT COUNT(*) FROM pi_space_member
            WHERE tenant_id = #{tenantId} AND role = 'ADMIN'
            """)
    long countAdmins(@Param("tenantId") String tenantId);
}
