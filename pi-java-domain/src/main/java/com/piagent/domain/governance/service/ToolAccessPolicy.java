package com.piagent.domain.governance.service;

import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.List;
import java.util.Optional;

/**
 * 工具访问策略解析器。
 *
 * <p>解析顺序：工作区精确规则 → 用户全局规则 → 工具风险默认值。
 * 默认值只用于没有显式规则的情况：LOW 直接放行，MEDIUM/HIGH 询问，CRITICAL 拒绝。
 * “未知工具”不在这里处理，工具目录查不到时已经失败。</p>
 */
public final class ToolAccessPolicy {

    /**
     * 解析工具在当前规则集下的效果。
     *
     * @param descriptor 工具定义
     * @param rules 当前工作区可用规则（含用户全局规则）
     * @return 访问效果
     */
    public PolicyEffect resolve(ToolDescriptor descriptor, List<ToolPolicyRule> rules) {
        List<ToolPolicyRule> matching = rules.stream()
                .filter(rule -> rule.key().toolName().equals(descriptor.name()))
                .toList();
        Optional<ToolPolicyRule> workspaceRule = firstOfScope(matching, PolicyScope.WORKSPACE);
        if (workspaceRule.isPresent()) {
            return workspaceRule.get().settings().effect();
        }
        Optional<ToolPolicyRule> userRule = firstOfScope(matching, PolicyScope.USER);
        if (userRule.isPresent()) {
            return userRule.get().settings().effect();
        }
        return defaultEffect(descriptor.risk());
    }

    /**
     * 按风险给出默认效果。
     *
     * @param risk 风险
     * @return 效果
     */
    public PolicyEffect defaultEffect(ToolRisk risk) {
        return switch (risk) {
            case LOW -> PolicyEffect.ALLOW;
            case MEDIUM, HIGH -> PolicyEffect.ASK;
            case CRITICAL -> PolicyEffect.DENY;
        };
    }

    private Optional<ToolPolicyRule> firstOfScope(List<ToolPolicyRule> rules, PolicyScope scope) {
        return rules.stream().filter(rule -> rule.key().scope() == scope).findFirst();
    }
}
