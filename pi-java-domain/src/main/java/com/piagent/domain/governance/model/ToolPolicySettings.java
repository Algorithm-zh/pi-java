package com.piagent.domain.governance.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.Map;
import java.util.Objects;

/**
 * 策略规则的决策内容。
 *
 * @param effect 访问效果
 * @param approvalMode 审批记忆模式
 * @param riskLevel 风险级别（可覆盖工具默认风险）
 * @param timeoutMs 执行超时覆盖，可为 null
 * @param config 扩展配置
 */
public record ToolPolicySettings(PolicyEffect effect, ApprovalMode approvalMode,
        ToolRisk riskLevel, Integer timeoutMs, Map<String, Object> config) {

    private static final int MIN_TIMEOUT_MS = 100;
    private static final int MAX_TIMEOUT_MS = 600_000;

    /**
     * 校验策略设置。
     *
     * @param effect 效果
     * @param approvalMode 审批模式
     * @param riskLevel 风险
     * @param timeoutMs 超时
     * @param config 配置
     */
    public ToolPolicySettings {
        Objects.requireNonNull(effect, "effect 不能为空");
        Objects.requireNonNull(approvalMode, "approvalMode 不能为空");
        Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
        if (timeoutMs != null) {
            Guard.require(timeoutMs >= MIN_TIMEOUT_MS && timeoutMs <= MAX_TIMEOUT_MS,
                    DomainErrorCode.INVALID_ARGUMENT,
                    "timeoutMs 必须在 " + MIN_TIMEOUT_MS + " 到 " + MAX_TIMEOUT_MS + " 之间");
        }
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    /**
     * 默认设置：保留工具风险，按需询问，单次审批。
     *
     * @param riskLevel 风险
     * @return 设置
     */
    public static ToolPolicySettings defaults(ToolRisk riskLevel) {
        return new ToolPolicySettings(PolicyEffect.ASK, ApprovalMode.ONCE, riskLevel, null, Map.of());
    }

    /**
     * 只读工具设置：直接放行。
     *
     * @param riskLevel 风险
     * @return 设置
     */
    public static ToolPolicySettings allow(ToolRisk riskLevel) {
        return new ToolPolicySettings(PolicyEffect.ALLOW, ApprovalMode.ONCE, riskLevel, null, Map.of());
    }
}
