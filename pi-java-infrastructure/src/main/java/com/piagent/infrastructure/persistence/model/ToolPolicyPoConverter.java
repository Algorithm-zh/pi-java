package com.piagent.infrastructure.persistence.model;

import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.infrastructure.persistence.codec.ToolPolicyConfigCodec;
import java.time.Instant;
import java.util.Map;

/**
 * `pi_tool_policy` 行与 {@link ToolPolicyRule} 聚合之间的转换。
 *
 * <p>枚举按名称映射；非法名称直接抛出 {@code IllegalArgumentException}，
 * 由仓储层视为数据损坏，而不是静默降级成默认策略。</p>
 */
public final class ToolPolicyPoConverter {

    private final ToolPolicyConfigCodec configCodec;

    /**
     * 创建转换器。
     *
     * @param configCodec 配置编解码
     */
    public ToolPolicyPoConverter(ToolPolicyConfigCodec configCodec) {
        this.configCodec = configCodec;
    }

    /**
     * 聚合转持久化对象。
     *
     * @param rule 规则
     * @param now 写入时间
     * @return 持久化对象
     */
    public ToolPolicyPO toPo(ToolPolicyRule rule, Instant now) {
        ToolPolicyPO po = new ToolPolicyPO();
        po.setId(rule.id());
        po.setScope(rule.key().scope().name());
        po.setWorkspaceKey(rule.key().workspaceKey().value());
        po.setToolName(rule.key().toolName().value());
        po.setEffect(rule.settings().effect().name());
        po.setApprovalMode(rule.settings().approvalMode().name());
        po.setRiskLevel(rule.settings().riskLevel().name());
        po.setTimeoutMs(rule.settings().timeoutMs());
        po.setConfigJson(configCodec.encode(rule.settings().config()));
        po.setVersion(rule.version());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 规则
     */
    public ToolPolicyRule toDomain(ToolPolicyPO po) {
        WorkspaceKey workspaceKey = new WorkspaceKey(po.getWorkspaceKey());
        ToolPolicyKey key = new ToolPolicyKey(PolicyScope.valueOf(po.getScope()), workspaceKey,
                new ToolName(po.getToolName()));
        Map<String, Object> config = configCodec.decode(po.getConfigJson());
        ToolPolicySettings settings = new ToolPolicySettings(
                PolicyEffect.valueOf(po.getEffect()),
                ApprovalMode.valueOf(po.getApprovalMode()),
                ToolRisk.valueOf(po.getRiskLevel()),
                po.getTimeoutMs(),
                config);
        return new ToolPolicyRule(po.getId(), key, settings, po.getVersion());
    }
}
