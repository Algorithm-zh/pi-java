package com.piagent.trigger.http.governance;

import com.piagent.application.governance.command.UpsertToolPolicyCommand;
import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.Map;

/**
 * 新增或更新工具策略请求。
 *
 * <p>{@code scope=USER} 时忽略 {@code cwd}；{@code scope=WORKSPACE} 时用
 * {@code cwd} 计算工作区键。{@code riskLevel} 为空表示沿用工具默认风险。
 * 配置对象只允许 JSON 对象，且不允许顶层 null 值，避免下游不可变复制失败。</p>
 *
 * @param scope 作用域：USER / WORKSPACE
 * @param cwd 工作目录，仅 WORKSPACE 使用
 * @param toolName 工具名
 * @param effect 访问效果：ALLOW / ASK / DENY
 * @param approvalMode 审批模式：ONCE / SESSION / ALWAYS
 * @param riskLevel 风险覆盖，可为 null
 * @param timeoutMs 超时覆盖，可为 null
 * @param config 扩展配置
 */
public record UpsertToolPolicyRequest(String scope, String cwd, String toolName, String effect,
        String approvalMode, String riskLevel, Integer timeoutMs, Map<String, Object> config) {

    /**
     * 校验配置对象。
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
    public UpsertToolPolicyRequest {
        if (config != null && config.containsValue(null)) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "config 不能包含 null 值");
        }
    }

    /**
     * 转换为应用层命令。
     *
     * @return 策略命令
     */
    public UpsertToolPolicyCommand toCommand() {
        return new UpsertToolPolicyCommand(
                EnumParse.parse(PolicyScope.class, scope, "scope"),
                cwd,
                Guard.notBlank(toolName, DomainErrorCode.INVALID_ARGUMENT, "toolName 不能为空"),
                EnumParse.parse(PolicyEffect.class, effect, "effect"),
                EnumParse.parse(ApprovalMode.class, approvalMode, "approvalMode"),
                riskLevel == null || riskLevel.isBlank()
                        ? null
                        : EnumParse.parse(ToolRisk.class, riskLevel, "riskLevel"),
                timeoutMs,
                config);
    }
}
