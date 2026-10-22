package com.piagent.infrastructure.persistence.extension;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `pi_plugin` 表 MyBatis 映射器。
 *
 * <p>状态字段按名称存取；更新不加版本条件，插件的启停是低频管理操作，
 * 由应用服务串行化状态迁移。</p>
 */
@Mapper
public interface PluginMapper {

    /**
     * 插入安装记录。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_plugin (id, version, api_version, jar_path, checksum, status,
                config_json, last_error, installed_at, updated_at)
            VALUES (#{id}, #{version}, #{apiVersion}, #{jarPath}, #{checksum}, #{status},
                #{configJson}, #{lastError}, #{installedAt}, #{updatedAt})
            """)
    int insert(PluginPO po);

    /**
     * 按主键更新可变字段。
     *
     * @param po 持久化对象
     * @return 影响行数，0 表示记录不存在
     */
    @Update("""
            UPDATE pi_plugin
            SET version = #{version},
                api_version = #{apiVersion},
                jar_path = #{jarPath},
                checksum = #{checksum},
                status = #{status},
                config_json = #{configJson},
                last_error = #{lastError},
                updated_at = #{updatedAt}
            WHERE id = #{id}
            """)
    int update(PluginPO po);

    /**
     * 按标识查询。
     *
     * @param id 插件标识
     * @return 持久化对象，不存在时为 null
     */
    @Select("SELECT * FROM pi_plugin WHERE id = #{id}")
    PluginPO findById(@Param("id") String id);

    /**
     * 查询全部安装记录。
     *
     * @return 按标识排序的记录
     */
    @Select("SELECT * FROM pi_plugin ORDER BY id ASC")
    List<PluginPO> findAll();
}
