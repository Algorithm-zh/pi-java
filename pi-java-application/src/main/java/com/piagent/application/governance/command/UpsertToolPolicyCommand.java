package com.piagent.application.governance.command;

import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.Map;
import java.util.Objects;

/**
 * 新增或更新一条工具策略命令。
 *
 * <p>{@code USER} 作用域忽略 {@code cwd}，规则对所有工作区生效；
 * {@code WORKSPACE} 作用域使用 {@code cwd} 计算工作区键。{@code riskLevel}
 * 为空时沿用工具定义的默认风险。</p>
 *
 * @param scope 作用域
 * @param cwd 工作目录，仅 WORKSPACE 使用
 * @param toolName 工具名
 * @param effect 访问效果
 * @param approvalMode 审批记忆模式
 * @param riskLevel 风险覆盖，可为 null
 * @param timeoutMs 超时覆盖，可为 null
 * @param config 扩展配置
 */
public record UpsertToolPolicyCommand(PolicyScope scope, String cwd, String toolName,
        PolicyEffect effect, ApprovalMode approvalMode, ToolRisk riskLevel, Integer timeoutMs,
        Map<String, Object> config) {

    /**
     * 校验命令。
     *
     * @param scope 作用域
     * @param cwd 工作目录
     * @param toolName 工具名
     * @param effect 效果
     * @param approvalMode 审批模式
     * @param riskLevel 风险
     * @param timeoutMs 超时
     * @param config 配置
     */
    public UpsertToolPolicyCommand {
        Objects.requireNonNull(scope, "scope 不能为空");
        toolName = Guard.notBlank(toolName, DomainErrorCode.INVALID_ARGUMENT, "toolName 不能为空");
        Objects.requireNonNull(effect, "effect 不能为空");
        Objects.requireNonNull(approvalMode, "approvalMode 不能为空");
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
