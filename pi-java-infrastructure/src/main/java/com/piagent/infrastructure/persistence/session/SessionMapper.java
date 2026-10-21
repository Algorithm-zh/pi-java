package com.piagent.infrastructure.persistence.session;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `pi_session` 表 MyBatis 映射器。
 */
@Mapper
public interface SessionMapper {

    /**
     * 插入会话。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_session (id, parent_session_id, root_session_id, fork_from_entry_id,
                depth, origin, title, cwd, model_profile_id, thinking_level, sandbox_json,
                status, leaf_entry_id, version, created_at, updated_at)
            VALUES (#{id}, #{parentSessionId}, #{rootSessionId}, #{forkFromEntryId},
                #{depth}, #{origin}, #{title}, #{cwd}, #{modelProfileId}, #{thinkingLevel},
                #{sandboxJson}, #{status}, #{leafEntryId}, #{version}, #{createdAt}, #{updatedAt})
            """)
    int insert(SessionPO po);

    /**
     * 以乐观锁更新会话。version 列自增，WHERE 条件使用旧版本。
     *
     * @param po 持久化对象
     * @return 影响行数，0 表示版本冲突或会话不存在
     */
    @Update("""
            UPDATE pi_session
            SET title = #{title},
                model_profile_id = #{modelProfileId},
                thinking_level = #{thinkingLevel},
                sandbox_json = #{sandboxJson},
                status = #{status},
                leaf_entry_id = #{leafEntryId},
                updated_at = #{updatedAt},
                version = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int update(SessionPO po);

    /**
     * 按标识查询。
     *
     * @param id 会话标识
     * @return 持久化对象，不存在时为 null
     */
    @Select("SELECT * FROM pi_session WHERE id = #{id}")
    SessionPO findById(@Param("id") String id);

    /**
     * 按工作目录查询最近会话。
     *
     * @param cwd 工作目录
     * @param limit 最大条数
     * @return 会话列表
     */
    @Select("SELECT * FROM pi_session WHERE cwd = #{cwd} ORDER BY updated_at DESC LIMIT #{limit}")
    List<SessionPO> findByCwd(@Param("cwd") String cwd, @Param("limit") int limit);

    /**
     * 查询直接子会话。
     *
     * @param parentId 父会话标识
     * @return 子会话列表
     */
    @Select("SELECT * FROM pi_session WHERE parent_session_id = #{parentId} ORDER BY created_at ASC")
    List<SessionPO> findChildren(@Param("parentId") String parentId);
}
