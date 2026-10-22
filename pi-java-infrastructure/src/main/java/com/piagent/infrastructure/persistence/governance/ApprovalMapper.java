package com.piagent.infrastructure.persistence.governance;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `pi_approval_request` 表 MyBatis 映射器。
 */
@Mapper
public interface ApprovalMapper {

    /**
     * 插入待审批请求。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_approval_request (id, session_id, run_id, tool_call_id, tool_name,
                args_digest, args_redacted_json, risk_level, status, reason, decided_by,
                decided_at, expires_at, created_at)
            VALUES (#{id}, #{sessionId}, #{runId}, #{toolCallId}, #{toolName},
                #{argsDigest}, #{argsRedactedJson}, #{riskLevel}, #{status}, #{reason},
                #{decidedBy}, #{decidedAt}, #{expiresAt}, #{createdAt})
            """)
    int insert(ApprovalPO po);

    /**
     * 按标识查询。
     *
     * @param id 审批标识
     * @return 持久化对象
     */
    @Select("SELECT * FROM pi_approval_request WHERE id = #{id}")
    ApprovalPO findById(@Param("id") String id);

    /**
     * 查询会话的全部审批记录。
     *
     * @param sessionId 会话标识
     * @return 记录
     */
    @Select("""
            SELECT * FROM pi_approval_request
            WHERE session_id = #{sessionId}
            ORDER BY created_at DESC, id DESC
            """)
    List<ApprovalPO> findBySession(@Param("sessionId") String sessionId);

    /**
     * 查询会话指定状态的审批记录。
     *
     * @param sessionId 会话标识
     * @param status 状态
     * @return 记录
     */
    @Select("""
            SELECT * FROM pi_approval_request
            WHERE session_id = #{sessionId} AND status = #{status}
            ORDER BY created_at DESC, id DESC
            """)
    List<ApprovalPO> findBySessionAndStatus(@Param("sessionId") String sessionId,
            @Param("status") String status);

    /**
     * 查询一次 run 的审批记录。
     *
     * @param runId 运行标识
     * @return 记录
     */
    @Select("""
            SELECT * FROM pi_approval_request
            WHERE run_id = #{runId}
            ORDER BY created_at ASC, id ASC
            """)
    List<ApprovalPO> findByRun(@Param("runId") String runId);

    /**
     * 仅当仍为 PENDING 时写入终态。
     *
     * @param po 含终态的持久化对象
     * @return 影响行数，0 表示已被其他决策抢先
     */
    @Update("""
            UPDATE pi_approval_request
            SET status = #{status},
                reason = #{reason},
                decided_by = #{decidedBy},
                decided_at = #{decidedAt}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int updateIfPending(ApprovalPO po);

    /**
     * 批量过期遗留待办。
     *
     * @param now 处理时间
     * @param reason 过期原因
     * @param decider 系统决策者
     * @return 影响行数
     */
    @Update("""
            UPDATE pi_approval_request
            SET status = 'EXPIRED',
                reason = #{reason},
                decided_by = #{decider},
                decided_at = #{now}
            WHERE status = 'PENDING'
            """)
    int expireAllPending(@Param("now") Instant now, @Param("reason") String reason,
            @Param("decider") String decider);
}
