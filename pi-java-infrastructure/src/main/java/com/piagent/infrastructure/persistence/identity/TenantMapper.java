package com.piagent.infrastructure.persistence.identity;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `pi_tenant` 表 MyBatis 映射器。
 */
@Mapper
public interface TenantMapper {

    /**
     * 插入租户。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_tenant (id, slug, name, created_at, deleted_at)
            VALUES (#{id}, #{slug}, #{name}, #{createdAt}, #{deletedAt})
            """)
    int insert(TenantPO po);

    /**
     * 按标识查询。
     *
     * @param id 租户标识
     * @return 持久化对象
     */
    @Select("SELECT * FROM pi_tenant WHERE id = #{id}")
    TenantPO findById(@Param("id") String id);

    /**
     * 按短名查询。
     *
     * @param slug 短名
     * @return 持久化对象
     */
    @Select("SELECT * FROM pi_tenant WHERE slug = #{slug}")
    TenantPO findBySlug(@Param("slug") String slug);

    /**
     * 查询全部租户。
     *
     * @return 按创建时间升序
     */
    @Select("SELECT * FROM pi_tenant ORDER BY created_at ASC, id ASC")
    List<TenantPO> findAll();

    /**
     * 查询全部未归档租户标识。
     *
     * @return 标识列表
     */
    @Select("""
            SELECT id FROM pi_tenant
            WHERE deleted_at IS NULL
            ORDER BY created_at ASC, id ASC
            """)
    List<String> findAllIds();

    /**
     * 写入归档时间。
     *
     * @param id 租户标识
     * @param deletedAt 归档时间；null 表示恢复
     * @return 影响行数
     */
    @Update("UPDATE pi_tenant SET deleted_at = #{deletedAt} WHERE id = #{id}")
    int updateDeletion(@Param("id") String id, @Param("deletedAt") Instant deletedAt);
}
