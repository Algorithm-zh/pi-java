package com.piagent.domain.extension.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class PluginInstallationTest {

    private static final Instant INSTALLED_AT = Instant.parse("2026-10-22T00:00:00Z");
    private static final Instant LATER = INSTALLED_AT.plusSeconds(30);

    @Test
    void installStartsAsInstalled() {
        PluginInstallation installation = installation();

        assertThat(installation.id().value()).isEqualTo("com.example.hello");
        assertThat(installation.status()).isEqualTo(PluginStatus.INSTALLED);
        assertThat(installation.lastError()).isNull();
        assertThat(installation.installedAt()).isEqualTo(INSTALLED_AT);
        assertThat(installation.updatedAt()).isEqualTo(INSTALLED_AT);
        assertThat(installation.artifact().configJson()).isEqualTo("{}");
    }

    @Test
    void activateAndDisableFollowStateMachine() {
        PluginInstallation installation = installation();

        installation.activate(LATER);
        assertThat(installation.status()).isEqualTo(PluginStatus.ACTIVE);
        assertThat(installation.updatedAt()).isEqualTo(LATER);

        installation.disable(LATER.plusSeconds(1));
        assertThat(installation.status()).isEqualTo(PluginStatus.DISABLED);
        assertThat(installation.updatedAt()).isEqualTo(LATER.plusSeconds(1));
    }

    @Test
    void cannotDisableBeforeActivationOrActivateTwice() {
        PluginInstallation installation = installation();

        assertThatThrownBy(() -> installation.disable(LATER))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_INVALID_STATE));

        installation.activate(LATER);
        assertThatThrownBy(() -> installation.activate(LATER))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_INVALID_STATE));
    }

    @Test
    void failedLoadRecordsErrorAndAllowsRetry() {
        PluginInstallation installation = installation();

        installation.fail("启动失败", LATER);
        assertThat(installation.status()).isEqualTo(PluginStatus.FAILED);
        assertThat(installation.lastError()).isEqualTo("启动失败");

        installation.activate(LATER.plusSeconds(1));
        assertThat(installation.status()).isEqualTo(PluginStatus.ACTIVE);
        assertThat(installation.lastError()).isNull();
    }

    @Test
    void errorMessageIsTruncated() {
        PluginInstallation installation = installation();

        installation.fail("x".repeat(2_000), LATER);

        assertThat(installation.lastError()).hasSize(1_024);
    }

    @Test
    void reconstituteRestoresSnapshot() {
        PluginInstallation installation = installation();
        installation.activate(LATER);
        PluginRecord record = installation.record();

        PluginInstallation restored = PluginInstallation.reconstitute(record);

        assertThat(restored.record()).isEqualTo(record);
        assertThat(restored.status()).isEqualTo(PluginStatus.ACTIVE);
    }

    private PluginInstallation installation() {
        PluginPackage pkg = new PluginPackage(new PluginId("com.example.hello"), "Hello",
                "1.0.0", new ApiVersion(1, 0), Path.of("plugins/hello.jar"),
                "0".repeat(64), "com.example.hello.HelloPlugin", "示例插件");
        return PluginInstallation.install(pkg, null, INSTALLED_AT);
    }
}
