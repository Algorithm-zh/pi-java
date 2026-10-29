package com.piagent.infrastructure.persistence.model;

import com.piagent.application.identity.AuthContext;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的工具策略仓储。
 *
 * <p>查询同时返回工作区规则与用户全局规则，优先级由领域服务解析；
 * 更新以 version 做 CAS，冲突统一报 {@code CONCURRENT_MODIFICATION}。
 * 全部读写按当前租户隔离，同名工作区在不同租户下互不可见。</p>
 */
@Repository
public class MyBatisToolPolicyRepository implements ToolPolicyRepository {

    private final ToolPolicyMapper mapper;
    private final ToolPolicyPoConverter converter;
    private final Clock clock;

    /**
     * 创建仓储。
     *
     * @param mapper MyBatis 映射器
     * @param converter 行转换器
     * @param clock 时钟
     */
    public MyBatisToolPolicyRepository(ToolPolicyMapper mapper, ToolPolicyPoConverter converter,
            Clock clock) {
        this.mapper = mapper;
        this.converter = converter;
        this.clock = clock;
    }

    @Override
    public List<ToolPolicyRule> findByWorkspace(WorkspaceKey workspaceKey) {
        return mapper.findByWorkspace(tenantId(), workspaceKey.value(),
                        WorkspaceKey.GLOBAL.value()).stream()
                .map(converter::toDomain)
                .toList();
    }

    @Override
    public Optional<ToolPolicyRule> find(ToolPolicyKey key) {
        ToolPolicyPO po = mapper.find(tenantId(), key.scope().name(), key.workspaceKey().value(),
                key.toolName().value());
        return Optional.ofNullable(po).map(converter::toDomain);
    }

    @Override
    public void insert(ToolPolicyRule rule) {
        mapper.insert(toPo(rule));
    }

    @Override
    public void update(ToolPolicyRule rule) {
        int rows = mapper.update(toPo(rule));
        if (rows == 0) {
            throw new DomainException(DomainErrorCode.CONCURRENT_MODIFICATION,
                    "工具策略已被其他写入修改，请重新加载: " + rule.key());
        }
    }

    private ToolPolicyPO toPo(ToolPolicyRule rule) {
        ToolPolicyPO po = converter.toPo(rule, clock.instant());
        po.setTenantId(tenantId());
        return po;
    }

    private String tenantId() {
        return AuthContext.tenantId().toString();
    }
}
