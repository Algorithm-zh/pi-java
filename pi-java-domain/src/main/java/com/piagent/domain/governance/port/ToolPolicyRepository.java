package com.piagent.domain.governance.port;

import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.WorkspaceKey;
import java.util.List;
import java.util.Optional;

/**
 * 工具策略仓储。
 *
 * <p>查询按工作区返回“工作区规则 + 用户全局规则”，由应用层交给
 * {@code ToolAccessPolicy} 解析优先级。更新必须使用 {@code rule.version()} 做 CAS。</p>
 */
public interface ToolPolicyRepository {

    /**
     * 查询某工作区可用的全部规则（含用户全局规则）。
     *
     * @param workspaceKey 工作区键
     * @return 规则列表
     */
    List<ToolPolicyRule> findByWorkspace(WorkspaceKey workspaceKey);

    /**
     * 按定位键精确查询。
     *
     * @param key 定位键
     * @return 规则
     */
    Optional<ToolPolicyRule> find(ToolPolicyKey key);

    /**
     * 插入新规则。
     *
     * @param rule 新规则
     */
    void insert(ToolPolicyRule rule);

    /**
     * 以 {@code rule.version()} 做 CAS 更新。
     *
     * @param rule 已有规则
     */
    void update(ToolPolicyRule rule);
}
