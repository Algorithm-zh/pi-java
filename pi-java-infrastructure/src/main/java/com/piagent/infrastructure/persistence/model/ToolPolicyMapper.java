package com.piagent.infrastructure.persistence.model;

import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * `pi_tool_policy` 表 MyBatis 映射器。
 */
@Mapper
public interface ToolPolicyMapper {

    /**
     * 插入规则并回填自增主键。
     *
     * @param po 持久化对象
     * @return 影响行数
     */
    @Insert("""
            INSERT INTO pi_tool_policy (scope, workspace_key, tool_name, effect, approval_mode,
                risk_level, timeout_ms, config_json, version, created_at, updated_at)
            VALUES (#{scope}, #{workspaceKey}, #{toolName}, #{effect}, #{approvalMode},
                #{riskLevel}, #{timeoutMs}, #{configJson}, #{version}, #{createdAt}, #{updatedAt})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(ToolPolicyPO po);

    /**
     * 以乐观锁更新规则。
     *
     * @param po 持久化对象
     * @return 影响行数，0 表示版本冲突或规则不存在
     */
    @Update("""
            UPDATE pi_tool_policy
            SET effect = #{effect},
                approval_mode = #{approvalMode},
                risk_level = #{riskLevel},
                timeout_ms = #{timeoutMs},
                config_json = #{configJson},
                updated_at = #{updatedAt},
                version = version + 1
            WHERE id = #{id} AND version = #{version}
            """)
    int update(ToolPolicyPO po);

    /**
     * 查询工作区规则与用户全局规则。
     *
     * @param workspaceKey 工作区键
     * @param globalKey 全局键
     * @return 规则列表
     */
    @Select("""
            SELECT * FROM pi_tool_policy
            WHERE workspace_key = #{workspaceKey} OR workspace_key = #{globalKey}
            ORDER BY id ASC
            """)
    List<ToolPolicyPO> findByWorkspace(@Param("workspaceKey") String workspaceKey,
            @Param("globalKey") String globalKey);

    /**
     * 按定位键查询规则。
     *
     * @param scope 作用域
     * @param workspaceKey 工作区键
     * @param toolName 工具名
     * @return 持久化对象，不存在时为 null
     */
    @Select("""
            SELECT * FROM pi_tool_policy
            WHERE scope = #{scope} AND workspace_key = #{workspaceKey} AND tool_name = #{toolName}
            """)
    ToolPolicyPO find(@Param("scope") String scope, @Param("workspaceKey") String workspaceKey,
            @Param("toolName") String toolName);
}
