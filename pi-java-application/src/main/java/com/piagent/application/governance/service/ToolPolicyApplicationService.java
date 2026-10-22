package com.piagent.application.governance.service;

import com.piagent.application.governance.command.UpsertToolPolicyCommand;
import com.piagent.application.governance.readmodel.ToolPolicyView;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.port.ToolRegistry;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 工具策略管理用例：查询本工作区可用规则、按作用域新增或更新规则。
 *
 * <p>写入前先确认工具存在，避免为拼写错误的工具名留下永远不生效的规则；
 * 更新走聚合根的 {@code withSettings} 与仓储 CAS，成功后返回递增后的版本。
 * 未提供风险级别时沿用工具定义的默认风险，而不是猜测一个固定值。</p>
 */
@Service
public class ToolPolicyApplicationService {

    private final ToolPolicyRepository repository;
    private final ToolRegistry toolRegistry;

    /**
     * 创建策略管理用例。
     *
     * @param repository 策略仓储
     * @param toolRegistry 工具目录
     */
    public ToolPolicyApplicationService(ToolPolicyRepository repository,
            ToolRegistry toolRegistry) {
        this.repository = repository;
        this.toolRegistry = toolRegistry;
    }

    /**
     * 查询工作区可见的全部策略规则（工作区规则 + 用户全局规则）。
     *
     * @param cwd 工作目录，空白表示进程当前目录
     * @return 策略读模型
     */
    @Transactional(readOnly = true)
    public List<ToolPolicyView> list(String cwd) {
        WorkspaceKey workspaceKey = WorkspaceKey.of(parseWorkspace(cwd));
        return repository.findByWorkspace(workspaceKey).stream()
                .map(ToolPolicyView::from)
                .toList();
    }

    /**
     * 新增或更新一条策略规则。
     *
     * @param command 策略命令
     * @return 保存后的读模型，版本为最新值
     */
    @Transactional
    public ToolPolicyView upsert(UpsertToolPolicyCommand command) {
        ToolName toolName = new ToolName(command.toolName());
        ToolDescriptor descriptor = toolRegistry.find(toolName).orElseThrow(() ->
                new DomainException(DomainErrorCode.TOOL_NOT_FOUND,
                        "工具不存在: " + toolName.value()));
        ToolPolicyKey key = keyOf(command, toolName);
        ToolPolicySettings settings = new ToolPolicySettings(command.effect(),
                command.approvalMode(),
                command.riskLevel() == null ? descriptor.risk() : command.riskLevel(),
                command.timeoutMs(), command.config());
        return repository.find(key)
                .map(current -> update(current, settings))
                .orElseGet(() -> insert(key, settings));
    }

    private ToolPolicyView update(ToolPolicyRule current, ToolPolicySettings settings) {
        ToolPolicyRule updated = current.withSettings(settings);
        repository.update(updated);
        return ToolPolicyView.from(updated.withVersion(current.version() + 1));
    }

    private ToolPolicyView insert(ToolPolicyKey key, ToolPolicySettings settings) {
        ToolPolicyRule created = ToolPolicyRule.create(key, settings);
        repository.insert(created);
        return repository.find(key)
                .map(ToolPolicyView::from)
                .orElseGet(() -> ToolPolicyView.from(created));
    }

    private ToolPolicyKey keyOf(UpsertToolPolicyCommand command, ToolName toolName) {
        if (command.scope() == PolicyScope.USER) {
            return ToolPolicyKey.user(toolName);
        }
        return ToolPolicyKey.workspace(WorkspaceKey.of(parseWorkspace(command.cwd())), toolName);
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
