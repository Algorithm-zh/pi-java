package com.piagent.infrastructure.persistence.session;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * `pi_session_entry` 表 MyBatis 映射器。
 */
@Mapper
public interface SessionEntryMapper {

    /**
     * 追加 entry。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_session_entry (id, session_id, seq, parent_id, entry_type,
                payload_version, payload_json, token_estimate, created_at)
            VALUES (#{id}, #{sessionId}, #{seq}, #{parentId}, #{entryType},
                #{payloadVersion}, #{payloadJson}, #{tokenEstimate}, #{createdAt})
            """)
    int insert(SessionEntryPO po);

    /**
     * 加载会话的全部 entry，按 seq 升序。
     *
     * @param sessionId 会话标识
     * @return entry 列表
     */
    @Select("SELECT * FROM pi_session_entry WHERE session_id = #{sessionId} ORDER BY seq ASC")
    List<SessionEntryPO> findAllBySession(@Param("sessionId") String sessionId);

    /**
     * 查询单条 entry。
     *
     * @param sessionId 会话标识
     * @param entryId entry 标识
     * @return 持久化对象，不存在时为 null
     */
    @Select("SELECT * FROM pi_session_entry WHERE session_id = #{sessionId} AND id = #{entryId}")
    SessionEntryPO findById(@Param("sessionId") String sessionId, @Param("entryId") String entryId);

    /**
     * 统计会话 entry 数量。
     *
     * @param sessionId 会话标识
     * @return 数量
     */
    @Select("SELECT COUNT(*) FROM pi_session_entry WHERE session_id = #{sessionId}")
    long countBySession(@Param("sessionId") String sessionId);
}
