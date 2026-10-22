package com.piagent.app.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.app.PiJavaApplication;
import com.piagent.domain.extension.model.ApiVersion;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.extension.model.PluginStatus;
import com.piagent.domain.extension.port.PluginRepository;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * H2 + Flyway + MyBatis 的插件安装记录集成测试。
 *
 * <p>覆盖 V4 表结构、配置与状态往返、排序、更新和主键冲突；不加载真实插件。</p>
 */
@SpringBootTest(classes = PiJavaApplication.class)
@ActiveProfiles("test")
class PluginPersistenceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    @Autowired
    private PluginRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM pi_plugin");
    }

    @Test
    void roundTripPreservesArtifactAndStatus() {
        PluginInstallation installation = installation("example.hello", "{\"mode\":\"fast\"}");
        repository.insert(installation);

        PluginInstallation loaded = repository.findById(installation.id()).orElseThrow();

        assertThat(loaded.id()).isEqualTo(installation.id());
        assertThat(loaded.artifact()).isEqualTo(installation.artifact());
        assertThat(loaded.status()).isEqualTo(PluginStatus.INSTALLED);
        assertThat(loaded.installedAt()).isEqualTo(NOW);
        assertThat(loaded.updatedAt()).isEqualTo(NOW);
        assertThat(loaded.lastError()).isNull();
    }

    @Test
    void updatePersistsActivationAndFailure() {
        PluginInstallation installation = installation("example.hello", null);
        repository.insert(installation);

        installation.activate(NOW.plusSeconds(1));
        repository.update(installation);
        assertThat(repository.findById(installation.id()).orElseThrow().status())
                .isEqualTo(PluginStatus.ACTIVE);

        installation.fail("入口类不存在", NOW.plusSeconds(2));
        repository.update(installation);
        PluginInstallation failed = repository.findById(installation.id()).orElseThrow();
        assertThat(failed.status()).isEqualTo(PluginStatus.FAILED);
        assertThat(failed.lastError()).isEqualTo("入口类不存在");
        assertThat(failed.updatedAt()).isEqualTo(NOW.plusSeconds(2));
    }

    @Test
    void findsAllOrderedById() {
        repository.insert(installation("example.b", null));
        repository.insert(installation("example.a", null));

        assertThat(repository.findAll()).extracting(item -> item.id().value())
                .containsExactly("example.a", "example.b");
    }

    @Test
    void duplicateIdIsRejectedByPrimaryKey() {
        repository.insert(installation("example.hello", null));

        assertThatThrownBy(() -> repository.insert(installation("example.hello", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownPluginReturnsEmpty() {
        assertThat(repository.findById(new PluginId("example.missing"))).isEmpty();
    }

    private PluginInstallation installation(String id, String configJson) {
        PluginPackage pkg = new PluginPackage(new PluginId(id), "Plugin " + id, "1.0.0",
                new ApiVersion(1, 0), Path.of("build", "test-plugins", id + ".jar"),
                "c".repeat(64), "example.HelloPlugin", "test plugin");
        return PluginInstallation.install(pkg, configJson, NOW);
    }
}
