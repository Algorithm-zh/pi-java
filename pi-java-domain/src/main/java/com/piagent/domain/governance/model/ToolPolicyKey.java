package com.piagent.domain.governance.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolName;
import java.util.Objects;

/**
 * 策略规则的定位键。
 *
 * <p>用户级规则必须使用 {@link WorkspaceKey#GLOBAL}；工作区级规则必须使用真实
 * 工作区键。这个不变量保证唯一索引 {@code (scope, workspace_key, tool_name)}
 * 不会被两种语义混用。</p>
 *
 * @param scope 作用域
 * @param workspaceKey 工作区键
 * @param toolName 工具名
 */
public record ToolPolicyKey(PolicyScope scope, WorkspaceKey workspaceKey, ToolName toolName) {

    /**
     * 校验定位键。
     *
     * @param scope 作用域
     * @param workspaceKey 工作区键
     * @param toolName 工具名
     */
    public ToolPolicyKey {
        Objects.requireNonNull(scope, "scope 不能为空");
        Objects.requireNonNull(workspaceKey, "workspaceKey 不能为空");
        Objects.requireNonNull(toolName, "toolName 不能为空");
        Guard.require(scope != PolicyScope.USER || workspaceKey.equals(WorkspaceKey.GLOBAL),
                DomainErrorCode.INVALID_ARGUMENT, "USER 规则必须使用全局工作区键");
        Guard.require(scope != PolicyScope.WORKSPACE || !workspaceKey.equals(WorkspaceKey.GLOBAL),
                DomainErrorCode.INVALID_ARGUMENT, "WORKSPACE 规则不能使用全局工作区键");
    }

    /**
     * 用户级规则键。
     *
     * @param toolName 工具名
     * @return 键
     */
    public static ToolPolicyKey user(ToolName toolName) {
        return new ToolPolicyKey(PolicyScope.USER, WorkspaceKey.GLOBAL, toolName);
    }

    /**
     * 工作区级规则键。
     *
     * @param workspaceKey 工作区键
     * @param toolName 工具名
     * @return 键
     */
    public static ToolPolicyKey workspace(WorkspaceKey workspaceKey, ToolName toolName) {
        return new ToolPolicyKey(PolicyScope.WORKSPACE, workspaceKey, toolName);
    }
}
