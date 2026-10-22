package com.piagent.application.testsupport;

import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 应用层测试用的内存工具策略仓储。
 *
 * <p>单工作区场景下直接返回全部规则，足以验证策略优先级与默认值；
 * 多工作区隔离由持久化集成测试覆盖。</p>
 */
public final class InMemoryToolPolicyRepository implements ToolPolicyRepository {

    private final List<ToolPolicyRule> rules = new ArrayList<>();

    @Override
    public List<ToolPolicyRule> findByWorkspace(WorkspaceKey workspaceKey) {
        return List.copyOf(rules);
    }

    @Override
    public Optional<ToolPolicyRule> find(ToolPolicyKey key) {
        return rules.stream().filter(rule -> rule.key().equals(key)).findFirst();
    }

    @Override
    public void insert(ToolPolicyRule rule) {
        rules.add(rule);
    }

    @Override
    public void update(ToolPolicyRule rule) {
        rules.removeIf(existing -> existing.key().equals(rule.key()));
        rules.add(rule);
    }
}
