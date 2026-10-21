package com.piagent.infrastructure.persistence.model;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `pi_model_profile` 表 MyBatis 映射器。
 */
@Mapper
public interface ModelProfileMapper {

    /**
     * 插入模型档案。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_model_profile (id, display_name, provider, base_url, api_key_env,
                model_name, context_window, max_output_tokens, capabilities_json, enabled,
                version, created_at, updated_at)
            VALUES (#{id}, #{displayName}, #{provider}, #{baseUrl}, #{apiKeyEnv},
                #{modelName}, #{contextWindow}, #{maxOutputTokens}, #{capabilitiesJson}, #{enabled},
                #{version}, #{createdAt}, #{updatedAt})
            """)
    int insert(ModelProfilePO po);

    /**
     * 以乐观锁更新模型档案。
     *
     * @param po 持久化对象
     * @return 影响行数，0 表示版本冲突或档案不存在
     */
    @Update("""
            UPDATE pi_model_profile
            SET display_name = #{displayName},
                provider = #{provider},
                base_url = #{baseUrl},
                api_key_env = #{apiKeyEnv},
                model_name = #{modelName},
                context_window = #{contextWindow},
                max_output_tokens = #{maxOutputTokens},
                capabilities_json = #{capabilitiesJson},
                enabled = #{enabled},
                updated_at = #{updatedAt},
                version = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int update(ModelProfilePO po);

    /**
     * 按标识查询。
     *
     * @param id 档案标识
     * @return 持久化对象，不存在时为 null
     */
    @Select("SELECT * FROM pi_model_profile WHERE id = #{id}")
    ModelProfilePO findById(@Param("id") String id);

    /**
     * 查询全部档案。
     *
     * @return 档案列表
     */
    @Select("SELECT * FROM pi_model_profile ORDER BY id ASC")
    List<ModelProfilePO> findAll();

    /**
     * 查询启用档案。
     *
     * @return 启用档案列表
     */
    @Select("SELECT * FROM pi_model_profile WHERE enabled = TRUE ORDER BY id ASC")
    List<ModelProfilePO> findEnabled();
}
