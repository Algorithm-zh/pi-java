package com.piagent.domain.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.service.ToolAccessPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 工作区键、策略规则与访问策略解析测试。
 */
class ToolPolicyTest {

    private static final WorkspaceKey WORKSPACE = WorkspaceKey.of(Path.of("D:/code/demo"));
    private static final ToolAccessPolicy POLICY = new ToolAccessPolicy();

    @Test
    void workspaceKeyNormalizesSeparatorsAndTrailingSlash() {
        WorkspaceKey key = WorkspaceKey.of(Path.of("D:/code/demo/"));
        WorkspaceKey same = WorkspaceKey.of(Path.of("D:\\code\\demo"));

        assertThat(key.value()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(key).isEqualTo(same).isEqualTo(WORKSPACE);
        assertThat(WorkspaceKey.GLOBAL.value()).isEqualTo("0".repeat(64));
    }

    @Test
    void workspaceKeyDiffersForDifferentDirectories() {
        WorkspaceKey other = WorkspaceKey.of(Path.of("D:/code/other"));
        assertThat(other).isNotEqualTo(WORKSPACE);
    }

    @Test
    void userRuleRequiresGlobalKeyAndWorkspaceRuleRequiresRealKey() {
        ToolName read = new ToolName("read");

        assertThat(ToolPolicyKey.user(read).workspaceKey()).isEqualTo(WorkspaceKey.GLOBAL);
        assertThatThrownBy(() -> new ToolPolicyKey(
                com.piagent.domain.governance.model.PolicyScope.USER, WORKSPACE, read))
                .isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> ToolPolicyKey.workspace(WorkspaceKey.GLOBAL, read))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void newRuleStartsAtVersionOneAndVersionCannotRollBack() {
        ToolPolicyRule rule = ToolPolicyRule.create(ToolPolicyKey.workspace(WORKSPACE,
                new ToolName("read")), ToolPolicySettings.allow(ToolRisk.LOW));

        assertThat(rule.id()).isZero();
        assertThat(rule.version()).isEqualTo(1);
        assertThat(rule.withVersion(2).version()).isEqualTo(2);
        assertThatThrownBy(() -> rule.withVersion(2).withVersion(1))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.INVALID_ARGUMENT));
    }

    @Test
    void defaultsFollowRiskLevel() {
        assertThat(POLICY.defaultEffect(ToolRisk.LOW)).isEqualTo(PolicyEffect.ALLOW);
        assertThat(POLICY.defaultEffect(ToolRisk.MEDIUM)).isEqualTo(PolicyEffect.ASK);
        assertThat(POLICY.defaultEffect(ToolRisk.HIGH)).isEqualTo(PolicyEffect.ASK);
        assertThat(POLICY.defaultEffect(ToolRisk.CRITICAL)).isEqualTo(PolicyEffect.DENY);
    }

    @Test
    void workspaceRuleOverridesUserRule() {
        ToolDescriptor read = descriptor("read", ToolRisk.LOW);
        ToolPolicyRule userAllow = rule(ToolPolicyKey.user(read.name()),
                PolicyEffect.ALLOW);
        ToolPolicyRule workspaceDeny = rule(ToolPolicyKey.workspace(WORKSPACE, read.name()),
                PolicyEffect.DENY);

        assertThat(POLICY.resolve(read, List.of(workspaceDeny, userAllow)))
                .isEqualTo(PolicyEffect.DENY);
        assertThat(POLICY.resolve(read, List.of(userAllow))).isEqualTo(PolicyEffect.ALLOW);
    }

    @Test
    void ruleOnlyAffectsMatchingTool() {
        ToolDescriptor read = descriptor("read", ToolRisk.LOW);
        ToolPolicyRule denyRead = rule(ToolPolicyKey.workspace(WORKSPACE, read.name()),
                PolicyEffect.DENY);
        ToolDescriptor ls = descriptor("ls", ToolRisk.LOW);

        assertThat(POLICY.resolve(read, List.of(denyRead))).isEqualTo(PolicyEffect.DENY);
        assertThat(POLICY.resolve(ls, List.of(denyRead))).isEqualTo(PolicyEffect.ALLOW);
    }

    @Test
    void settingsValidateTimeoutAndCopyConfig() {
        Map<String, Object> config = new java.util.HashMap<>();
        config.put("key", "value");
        ToolPolicySettings settings = new ToolPolicySettings(PolicyEffect.ASK,
                ApprovalMode.SESSION, ToolRisk.HIGH, 5_000, config);
        config.put("key", "changed");

        assertThat(settings.config()).containsEntry("key", "value");
        assertThatThrownBy(() -> new ToolPolicySettings(PolicyEffect.ASK, ApprovalMode.ONCE,
                ToolRisk.HIGH, 10, Map.of()))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("timeoutMs");
    }

    private static ToolPolicyRule rule(ToolPolicyKey key, PolicyEffect effect) {
        return ToolPolicyRule.create(key, new ToolPolicySettings(effect,
                ApprovalMode.ONCE, ToolRisk.LOW, null, Map.of()));
    }

    private static ToolDescriptor descriptor(String name, ToolRisk risk) {
        return new ToolDescriptor(new ToolName(name), "工具 " + name, "{}", risk,
                ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }
}
