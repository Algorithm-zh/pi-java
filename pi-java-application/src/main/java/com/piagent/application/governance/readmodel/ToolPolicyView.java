package com.piagent.application.governance.readmodel;

import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.Map;

/**
 * 工具策略读模型。
 *
 * <p>保留作用域、工作区键与版本，前端可以区分用户全局规则与本工作区规则，
 * 并在编辑时回传版本做乐观锁校验。</p>
 *
 * @param id 主键
 * @param scope 作用域
 * @param workspaceKey 工作区键
 * @param toolName 工具名
 * @param effect 访问效果
 * @param approvalMode 审批模式
 * @param riskLevel 风险级别
 * @param timeoutMs 超时覆盖，可为 null
 * @param config 扩展配置
 * @param version 乐观锁版本
 */
public record ToolPolicyView(long id, PolicyScope scope, String workspaceKey, String toolName,
        PolicyEffect effect, ApprovalMode approvalMode, ToolRisk riskLevel, Integer timeoutMs,
        Map<String, Object> config, long version) {

    /**
     * 从聚合根构造读模型。
     *
     * @param rule 策略规则
     * @return 读模型
     */
    public static ToolPolicyView from(ToolPolicyRule rule) {
        return new ToolPolicyView(rule.id(), rule.key().scope(), rule.key().workspaceKey().value(),
                rule.key().toolName().value(), rule.settings().effect(),
                rule.settings().approvalMode(), rule.settings().riskLevel(),
                rule.settings().timeoutMs(), rule.settings().config(), rule.version());
    }
}
