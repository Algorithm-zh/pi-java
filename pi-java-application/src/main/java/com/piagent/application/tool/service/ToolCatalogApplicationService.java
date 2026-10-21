package com.piagent.application.tool.service;

import com.piagent.application.tool.readmodel.ToolView;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.governance.service.ToolAccessPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.port.ToolRegistry;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具目录查询用例。
 *
 * <p>把“当前工作区能看到哪些工具、每个工具是放行/询问/拒绝”合并成一个读模型。
 * 策略解析只读数据库与领域服务，不触发审批，也不改变任何状态。</p>
 */
@Service
public class ToolCatalogApplicationService {

    private final ToolRegistry toolRegistry;
    private final ToolPolicyRepository policyRepository;
    private final ToolAccessPolicy accessPolicy;

    /**
     * 创建用例服务。
     *
     * @param toolRegistry 工具目录
     * @param policyRepository 策略仓储
     * @param accessPolicy 策略解析器
     */
    public ToolCatalogApplicationService(ToolRegistry toolRegistry,
            ToolPolicyRepository policyRepository, ToolAccessPolicy accessPolicy) {
        this.toolRegistry = toolRegistry;
        this.policyRepository = policyRepository;
        this.accessPolicy = accessPolicy;
    }

    /**
     * 查询指定工作区可用的工具。
     *
     * @param cwd 工作目录，空白表示进程当前目录
     * @return 按名称排序的工具读模型
     */
    @Transactional(readOnly = true)
    public List<ToolView> list(String cwd) {
        WorkspaceKey workspaceKey = WorkspaceKey.of(parseWorkspace(cwd));
        List<ToolPolicyRule> rules = policyRepository.findByWorkspace(workspaceKey);
        return toolRegistry.descriptors().stream()
                .map(descriptor -> ToolView.from(descriptor,
                        accessPolicy.resolve(descriptor, rules)))
                .sorted(Comparator.comparing(ToolView::name))
                .toList();
    }

    private Path parseWorkspace(String cwd) {
        String value = cwd == null || cwd.isBlank() ? "." : cwd.strip();
        try {
            return Path.of(value);
        } catch (InvalidPathException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "cwd 不是合法路径: " + cwd, error);
        }
    }
}
