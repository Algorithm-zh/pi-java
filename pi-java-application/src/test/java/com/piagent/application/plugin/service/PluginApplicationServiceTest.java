package com.piagent.application.plugin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.application.plugin.command.InstallPluginCommand;
import com.piagent.application.testsupport.InMemoryPluginRepository;
import com.piagent.application.testsupport.RecordingPluginRuntime;
import com.piagent.domain.extension.model.ApiVersion;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.extension.model.PluginStatus;
import com.piagent.domain.extension.port.PluginPackageInspector;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件用例服务测试：安装、启停状态机与启动恢复。
 */
class PluginApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");

    @TempDir
    Path tempDir;

    private final Map<Path, PluginPackage> packages = new LinkedHashMap<>();
    private InMemoryPluginRepository repository;
    private RecordingPluginRuntime runtime;
    private PluginApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryPluginRepository();
        runtime = new RecordingPluginRuntime();
        PluginPackageInspector inspector = path -> {
            PluginPackage pkg = packages.get(path);
            if (pkg == null) {
                throw new DomainException(DomainErrorCode.PLUGIN_LOAD_FAILED, "找不到测试 jar");
            }
            return pkg;
        };
        service = new PluginApplicationService(repository, runtime, inspector,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void installsAsInstalledWithoutLoadingCode() {
        PluginInstallation installation = service.install(
                new InstallPluginCommand(jar("example.hello"), "{\"mode\":\"fast\"}"));

        assertThat(installation.status()).isEqualTo(PluginStatus.INSTALLED);
        assertThat(installation.artifact().configJson()).isEqualTo("{\"mode\":\"fast\"}");
        assertThat(installation.installedAt()).isEqualTo(NOW);
        assertThat(runtime.loadCount()).isZero();
        assertThat(service.find("example.hello").status()).isEqualTo(PluginStatus.INSTALLED);
    }

    @Test
    void rejectsDuplicateInstallation() {
        service.install(new InstallPluginCommand(jar("example.hello"), null));

        assertCode(() -> service.install(new InstallPluginCommand(jar("example.hello"), null)),
                DomainErrorCode.PLUGIN_ALREADY_EXISTS);
    }

    @Test
    void enableLoadsRuntimeAndPersistsActive() {
        service.install(new InstallPluginCommand(jar("example.hello"), null));

        PluginInstallation enabled = service.enable("example.hello");

        assertThat(enabled.status()).isEqualTo(PluginStatus.ACTIVE);
        assertThat(runtime.isLoaded(new PluginId("example.hello"))).isTrue();
        assertThat(repository.findById(new PluginId("example.hello")).orElseThrow().status())
                .isEqualTo(PluginStatus.ACTIVE);
    }

    @Test
    void failedLoadMarksInstallationFailedAndCanRetry() {
        service.install(new InstallPluginCommand(jar("example.broken"), null));
        runtime.failOn(new PluginId("example.broken"));

        assertCode(() -> service.enable("example.broken"), DomainErrorCode.PLUGIN_LOAD_FAILED);
        PluginInstallation failed = repository.findById(new PluginId("example.broken")).orElseThrow();
        assertThat(failed.status()).isEqualTo(PluginStatus.FAILED);
        assertThat(failed.lastError()).contains("模拟加载失败");

        runtime.recover(new PluginId("example.broken"));
        PluginInstallation retried = service.enable("example.broken");
        assertThat(retried.status()).isEqualTo(PluginStatus.ACTIVE);
        assertThat(retried.lastError()).isNull();
    }

    @Test
    void enablingActivePluginIsIdempotent() {
        service.install(new InstallPluginCommand(jar("example.hello"), null));
        service.enable("example.hello");

        service.enable("example.hello");

        assertThat(runtime.loadCount()).isEqualTo(1);
    }

    @Test
    void disableUnloadsRuntimeAndIsIdempotent() {
        service.install(new InstallPluginCommand(jar("example.hello"), null));
        service.enable("example.hello");

        PluginInstallation disabled = service.disable("example.hello");
        PluginInstallation disabledAgain = service.disable("example.hello");

        assertThat(disabled.status()).isEqualTo(PluginStatus.DISABLED);
        assertThat(disabledAgain.status()).isEqualTo(PluginStatus.DISABLED);
        assertThat(runtime.isLoaded(new PluginId("example.hello"))).isFalse();
        assertThat(runtime.unloadCount()).isEqualTo(1);
    }

    @Test
    void disableRejectsPluginThatWasNeverEnabled() {
        service.install(new InstallPluginCommand(jar("example.hello"), null));

        assertCode(() -> service.disable("example.hello"), DomainErrorCode.PLUGIN_INVALID_STATE);
    }

    @Test
    void unknownPluginFailsAllOperations() {
        assertCode(() -> service.find("missing"), DomainErrorCode.PLUGIN_NOT_FOUND);
        assertCode(() -> service.enable("missing"), DomainErrorCode.PLUGIN_NOT_FOUND);
        assertCode(() -> service.disable("missing"), DomainErrorCode.PLUGIN_NOT_FOUND);
    }

    @Test
    void listsInstalledPluginsInOrder() {
        service.install(new InstallPluginCommand(jar("example.a"), null));
        service.install(new InstallPluginCommand(jar("example.b"), null));

        assertThat(service.list()).extracting(item -> item.id().value())
                .containsExactly("example.a", "example.b");
    }

    @Test
    void restoreActiveLoadsGoodPluginsAndMarksBrokenOnesFailed() {
        insertActive("example.good");
        insertActive("example.bad");
        runtime.failOn(new PluginId("example.bad"));
        service.install(new InstallPluginCommand(jar("example.idle"), null));

        int restored = service.restoreActive();

        assertThat(restored).isEqualTo(1);
        assertThat(runtime.isLoaded(new PluginId("example.good"))).isTrue();
        assertThat(repository.findById(new PluginId("example.bad")).orElseThrow().status())
                .isEqualTo(PluginStatus.FAILED);
        assertThat(repository.findById(new PluginId("example.idle")).orElseThrow().status())
                .isEqualTo(PluginStatus.INSTALLED);
    }

    @Test
    void restoreActiveSkipsAlreadyLoadedPlugin() {
        insertActive("example.good");

        assertThat(service.restoreActive()).isEqualTo(1);
        assertThat(service.restoreActive()).isEqualTo(1);
        assertThat(runtime.loadCount()).isEqualTo(1);
    }

    private void insertActive(String id) {
        PluginInstallation installation = PluginInstallation.install(pkg(id), null, NOW);
        installation.activate(NOW);
        repository.insert(installation);
    }

    private Path jar(String id) {
        Path path = tempDir.resolve(id + ".jar");
        packages.put(path, pkg(id));
        return path;
    }

    private PluginPackage pkg(String id) {
        return new PluginPackage(new PluginId(id), "Plugin " + id, "1.0.0",
                new ApiVersion(1, 0), tempDir.resolve(id + ".jar"), "a".repeat(64),
                "example.HelloPlugin", "test");
    }

    private void assertCode(Runnable action, DomainErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(expected));
    }
}
