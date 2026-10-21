package com.piagent.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.app.PiJavaApplication;
import com.piagent.domain.governance.model.ApprovalMode;
import com.piagent.domain.governance.model.PolicyEffect;
import com.piagent.domain.governance.model.ToolPolicyKey;
import com.piagent.domain.governance.model.ToolPolicyRule;
import com.piagent.domain.governance.model.ToolPolicySettings;
import com.piagent.domain.governance.model.WorkspaceKey;
import com.piagent.domain.governance.port.ToolPolicyRepository;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 + Flyway + MyBatis 的工具策略持久化集成测试。
 *
 * <p>覆盖工作区/用户规则查询、配置 JSON 往返、乐观锁与唯一键冲突。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
class ToolPolicyPersistenceIntegrationTest {

    private static final ToolName READ = new ToolName("read");

    @Autowired
    private ToolPolicyRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM pi_tool_policy");
    }

    @Test
    void roundTripPreservesSettings() {
        WorkspaceKey workspaceKey = WorkspaceKey.of(Path.of("D:/demo/ws-a"));
        ToolPolicyRule rule = ToolPolicyRule.create(
                ToolPolicyKey.workspace(workspaceKey, READ),
                new ToolPolicySettings(PolicyEffect.DENY, ApprovalMode.SESSION, ToolRisk.HIGH,
                        5_000, Map.of("reason", "audit")));
        repository.insert(rule);

        ToolPolicyRule loaded = repository.find(rule.key()).orElseThrow();

        assertThat(loaded.id()).isPositive();
        assertThat(loaded.key()).isEqualTo(rule.key());
        assertThat(loaded.settings()).isEqualTo(rule.settings());
        assertThat(loaded.version()).isEqualTo(ToolPolicyRule.INITIAL_VERSION);
    }

    @Test
    void workspaceQueryIncludesGlobalUserRulesOnly() {
        WorkspaceKey workspaceA = WorkspaceKey.of(Path.of("D:/demo/ws-a"));
        WorkspaceKey workspaceB = WorkspaceKey.of(Path.of("D:/demo/ws-b"));
        repository.insert(rule(ToolPolicyKey.user(READ), PolicyEffect.ASK));
        repository.insert(rule(ToolPolicyKey.workspace(workspaceA, READ), PolicyEffect.ALLOW));
        repository.insert(rule(ToolPolicyKey.workspace(workspaceB, READ), PolicyEffect.DENY));

        assertThat(repository.findByWorkspace(workspaceA))
                .extracting(item -> item.key().scope().name())
                .containsExactly("USER", "WORKSPACE");
        assertThat(repository.findByWorkspace(workspaceB)).hasSize(2);
        assertThat(repository.findByWorkspace(WorkspaceKey.of(Path.of("D:/demo/ws-c"))))
                .hasSize(1);
    }

    @Test
    void updateUsesOptimisticLock() {
        WorkspaceKey workspaceKey = WorkspaceKey.of(Path.of("D:/demo/ws-lock"));
        ToolPolicyRule rule = rule(ToolPolicyKey.workspace(workspaceKey, READ), PolicyEffect.ASK);
        repository.insert(rule);
        ToolPolicyRule loaded = repository.find(rule.key()).orElseThrow();

        repository.update(loaded.withSettings(new ToolPolicySettings(PolicyEffect.ALLOW,
                ApprovalMode.ALWAYS, ToolRisk.LOW, null, Map.of())));

        ToolPolicyRule updated = repository.find(rule.key()).orElseThrow();
        assertThat(updated.settings().effect()).isEqualTo(PolicyEffect.ALLOW);
        assertThat(updated.version()).isEqualTo(2);
        assertThatThrownBy(() -> repository.update(loaded.withSettings(new ToolPolicySettings(
                PolicyEffect.DENY, ApprovalMode.ONCE, ToolRisk.HIGH, null, Map.of()))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.CONCURRENT_MODIFICATION));
    }

    @Test
    void duplicateKeyIsRejected() {
        WorkspaceKey workspaceKey = WorkspaceKey.of(Path.of("D:/demo/ws-dup"));
        ToolPolicyKey key = ToolPolicyKey.workspace(workspaceKey, READ);
        repository.insert(rule(key, PolicyEffect.ASK));

        assertThatThrownBy(() -> repository.insert(rule(key, PolicyEffect.DENY)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ToolPolicyRule rule(ToolPolicyKey key, PolicyEffect effect) {
        return ToolPolicyRule.create(key, new ToolPolicySettings(effect, ApprovalMode.ONCE,
                ToolRisk.LOW, null, Map.of()));
    }
}
