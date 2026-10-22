package com.piagent.application.governance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.piagent.application.governance.command.UpsertToolPolicyCommand;
import com.piagent.application.governance.readmodel.ToolPolicyView;
import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.PolicyScope;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ToolPolicyApplicationServiceTest {

    private static final Path WORKSPACE = Path.of("D:/demo/workspace");

    private ToolPolicyRepository repository;

    private ToolRegistry registry;

    private ToolPolicyApplicationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ToolPolicyRepository.class);
        registry = mock(ToolRegistry.class);
        service = new ToolPolicyApplicationService(repository, registry);
    }

    @Test
    void insertsNewWorkspaceRuleWithToolDefaultRisk() {
        ToolName bash = new ToolName("bash");
        ToolPolicyKey key = ToolPolicyKey.workspace(WorkspaceKey.of(WORKSPACE), bash);
        ToolPolicySettings expected = new ToolPolicySettings(PolicyEffect.ALLOW, ApprovalMode.ONCE,
                ToolRisk.CRITICAL, 30_000, Map.of("note", "demo"));
        when(registry.find(bash)).thenReturn(Optional.of(descriptor("bash", ToolRisk.CRITICAL)));
        when(repository.find(key)).thenReturn(Optional.empty(),
                Optional.of(new ToolPolicyRule(7L, key, expected, 1L)));

        ToolPolicyView view = service.upsert(new UpsertToolPolicyCommand(
                PolicyScope.WORKSPACE, WORKSPACE.toString(), "bash", PolicyEffect.ALLOW,
                ApprovalMode.ONCE, null, 30_000, Map.of("note", "demo")));

        ArgumentCaptor<ToolPolicyRule> inserted = ArgumentCaptor.forClass(ToolPolicyRule.class);
        verify(repository).insert(inserted.capture());
        assertThat(inserted.getValue().id()).isZero();
        assertThat(inserted.getValue().settings()).isEqualTo(expected);
        assertThat(view.id()).isEqualTo(7L);
        assertThat(view.version()).isEqualTo(1L);
        assertThat(view.riskLevel()).isEqualTo(ToolRisk.CRITICAL);
        assertThat(view.config()).containsEntry("note", "demo");
    }

    @Test
    void updatesExistingRuleWithCompareAndSetVersion() {
        ToolName bash = new ToolName("bash");
        ToolPolicyKey key = ToolPolicyKey.workspace(WorkspaceKey.of(WORKSPACE), bash);
        ToolPolicyRule existing = new ToolPolicyRule(5L, key, settings(PolicyEffect.ASK), 3L);
        when(registry.find(bash)).thenReturn(Optional.of(descriptor("bash", ToolRisk.CRITICAL)));
        when(repository.find(key)).thenReturn(Optional.of(existing));

        ToolPolicyView view = service.upsert(new UpsertToolPolicyCommand(
                PolicyScope.WORKSPACE, WORKSPACE.toString(), "bash", PolicyEffect.ALLOW,
                ApprovalMode.ALWAYS, ToolRisk.HIGH, null, null));

        ArgumentCaptor<ToolPolicyRule> updated = ArgumentCaptor.forClass(ToolPolicyRule.class);
        verify(repository).update(updated.capture());
        assertThat(updated.getValue().id()).isEqualTo(5L);
        assertThat(updated.getValue().version()).isEqualTo(3L);
        assertThat(updated.getValue().settings().effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(updated.getValue().settings().approvalMode()).isEqualTo(ApprovalMode.ALWAYS);
        assertThat(view.version()).isEqualTo(4L);
        verify(repository, never()).insert(any());
    }

    @Test
    void userRuleUsesGlobalWorkspaceKeyAndIgnoresCwd() {
        ToolName read = new ToolName("read");
        ToolPolicyKey userKey = ToolPolicyKey.user(read);
        ToolPolicySettings expected = settings(PolicyEffect.DENY);
        when(registry.find(read)).thenReturn(Optional.of(descriptor("read", ToolRisk.LOW)));
        when(repository.find(userKey)).thenReturn(Optional.empty(),
                Optional.of(new ToolPolicyRule(2L, userKey, expected, 1L)));

        ToolPolicyView view = service.upsert(new UpsertToolPolicyCommand(PolicyScope.USER,
                "D:/ignored", "read", PolicyEffect.DENY, ApprovalMode.ONCE, ToolRisk.LOW, null,
                null));

        verify(repository, atLeastOnce()).find(userKey);
        assertThat(view.scope()).isEqualTo(PolicyScope.USER);
        assertThat(view.workspaceKey()).isEqualTo(WorkspaceKey.GLOBAL.value());
    }

    @Test
    void workspaceCwdIsNormalizedBeforeQuerying() {
        ToolName read = new ToolName("read");
        ToolPolicyKey key = ToolPolicyKey.workspace(WorkspaceKey.of(Path.of("D:/demo")), read);
        when(registry.find(read)).thenReturn(Optional.of(descriptor("read", ToolRisk.LOW)));
        when(repository.find(any())).thenReturn(Optional.empty());

        service.upsert(new UpsertToolPolicyCommand(PolicyScope.WORKSPACE,
                "D:/demo/../demo", "read", PolicyEffect.ALLOW, ApprovalMode.ONCE, null, null,
                null));

        verify(repository, atLeastOnce()).find(key);
        verify(repository).insert(any());
    }

    @Test
    void rejectsUnknownToolBeforeWriting() {
        ToolName ghost = new ToolName("ghost");
        when(registry.find(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(new UpsertToolPolicyCommand(
                PolicyScope.WORKSPACE, WORKSPACE.toString(), "ghost", PolicyEffect.ALLOW,
                ApprovalMode.ONCE, null, null, null)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_NOT_FOUND));
        verifyNoInteractions(repository);
    }

    @Test
    void listQueriesWorkspaceAndUserRules() {
        WorkspaceKey key = WorkspaceKey.of(WORKSPACE);
        ToolName read = new ToolName("read");
        ToolName bash = new ToolName("bash");
        when(repository.findByWorkspace(key)).thenReturn(List.of(
                new ToolPolicyRule(1L, ToolPolicyKey.user(read), settings(PolicyEffect.ALLOW), 1L),
                new ToolPolicyRule(2L, ToolPolicyKey.workspace(key, bash),
                        settings(PolicyEffect.ASK), 2L)));

        List<ToolPolicyView> views = service.list(WORKSPACE.toString());

        assertThat(views).extracting(ToolPolicyView::toolName)
                .containsExactly("read", "bash");
        assertThat(views).extracting(ToolPolicyView::scope)
                .containsExactly(PolicyScope.USER, PolicyScope.WORKSPACE);
    }

    private ToolPolicySettings settings(PolicyEffect effect) {
        return new ToolPolicySettings(effect, ApprovalMode.ONCE, ToolRisk.HIGH, null, Map.of());
    }

    private ToolDescriptor descriptor(String name, ToolRisk risk) {
        return new ToolDescriptor(new ToolName(name), "测试工具", "{}", risk,
                ExecutionMode.SEQUENTIAL, Duration.ofSeconds(5));
    }
}
