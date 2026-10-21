package com.piagent.application.tool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.piagent.application.tool.readmodel.ToolView;
import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.governance.service.ToolAccessPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.domain.tool.port.ToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ToolCatalogApplicationServiceTest {

    private ToolRegistry registry;
    private ToolPolicyRepository repository;
    private ToolCatalogApplicationService service;

    @BeforeEach
    void setUp() {
        registry = mock(ToolRegistry.class);
        repository = mock(ToolPolicyRepository.class);
        service = new ToolCatalogApplicationService(registry, repository, new ToolAccessPolicy());
    }

    @Test
    void appliesRiskDefaultsWhenNoRulesExist() {
        when(registry.descriptors()).thenReturn(List.of(
                descriptor("read", ToolRisk.LOW),
                descriptor("bash", ToolRisk.HIGH),
                descriptor("admin", ToolRisk.CRITICAL)));
        when(repository.findByWorkspace(any())).thenReturn(List.of());

        List<ToolView> views = service.list("D:/demo/workspace");

        assertThat(views).extracting(ToolView::name)
                .containsExactly("admin", "bash", "read");
        assertThat(views).extracting(ToolView::effect).containsExactly(
                PolicyEffect.DENY, PolicyEffect.ASK, PolicyEffect.ALLOW);
        assertThat(views.get(1).requiresApproval()).isTrue();
    }

    @Test
    void workspaceRuleOverridesUserRule() {
        WorkspaceKey workspaceKey = WorkspaceKey.of(Path.of("D:/demo/workspace"));
        ToolName read = new ToolName("read");
        when(registry.descriptors()).thenReturn(List.of(descriptor("read", ToolRisk.LOW)));
        when(repository.findByWorkspace(workspaceKey)).thenReturn(List.of(
                rule(ToolPolicyKey.user(read), PolicyEffect.DENY),
                rule(ToolPolicyKey.workspace(workspaceKey, read), PolicyEffect.ALLOW)));

        List<ToolView> views = service.list("D:/demo/workspace");

        assertThat(views).singleElement()
                .satisfies(view -> assertThat(view.effect()).isEqualTo(PolicyEffect.ALLOW));
    }

    @Test
    void queriesPolicyByNormalizedWorkspaceKey() {
        when(registry.descriptors()).thenReturn(List.of());
        when(repository.findByWorkspace(any())).thenReturn(List.of());

        service.list("D:/demo/workspace/../workspace");

        verify(repository).findByWorkspace(WorkspaceKey.of(Path.of("D:/demo/workspace")));
    }

    @Test
    void blankCwdUsesProcessDirectory() {
        when(registry.descriptors()).thenReturn(List.of());
        when(repository.findByWorkspace(any())).thenReturn(List.of());

        service.list("  ");

        verify(repository).findByWorkspace(WorkspaceKey.of(Path.of(".")));
    }

    @Test
    void rejectsInvalidCwd() {
        assertThatThrownBy(() -> service.list("bad\0path"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void missingPolicyIsNotAnError() {
        when(registry.descriptors()).thenReturn(List.of(descriptor("read", ToolRisk.LOW)));
        when(repository.findByWorkspace(any())).thenReturn(List.of());

        assertThat(service.list("D:/demo")).hasSize(1);
    }

    private ToolDescriptor descriptor(String name, ToolRisk risk) {
        return new ToolDescriptor(new ToolName(name), "测试工具", "{}", risk,
                ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }

    private ToolPolicyRule rule(ToolPolicyKey key, PolicyEffect effect) {
        ToolPolicySettings settings = new ToolPolicySettings(effect, ApprovalMode.ONCE,
                ToolRisk.LOW, null, Map.of());
        return new ToolPolicyRule(1L, key, settings, 1L);
    }
}
