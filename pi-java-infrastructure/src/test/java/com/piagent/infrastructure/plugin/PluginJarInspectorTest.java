package com.piagent.infrastructure.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.infrastructure.plugin.support.PluginJarFixture;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件 jar 检查器测试：元数据、完整性与拒绝规则。
 */
class PluginJarInspectorTest {

    @TempDir
    Path tempDir;

    private final PluginJarInspector inspector = new PluginJarInspector(new ObjectMapper());

    @Test
    void inspectsValidJar() throws IOException {
        Path jar = PluginJarFixture.hello(tempDir.resolve("hello"));

        PluginPackage pkg = inspector.inspect(jar);

        assertThat(pkg.id().value()).isEqualTo("example.hello");
        assertThat(pkg.name()).isEqualTo("Hello Plugin");
        assertThat(pkg.version()).isEqualTo("1.0.0");
        assertThat(pkg.apiVersion().text()).isEqualTo("1.0");
        assertThat(pkg.mainClass()).isEqualTo("example.hello.HelloPlugin");
        assertThat(pkg.jarPath()).isEqualTo(jar.toAbsolutePath().normalize());
        assertThat(pkg.checksum()).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void detectsReplacedJarByChecksum() throws IOException {
        Path jar = PluginJarFixture.hello(tempDir.resolve("hello"));
        String original = inspector.inspect(jar).checksum();

        Files.write(jar, new byte[]{0}, StandardOpenOption.APPEND);

        assertThat(inspector.inspect(jar).checksum()).isNotEqualTo(original);
    }

    @Test
    void rejectsJarWithoutManifest() throws IOException {
        Path jar = PluginJarFixture.missingManifest(tempDir.resolve("empty"));

        assertFailure(() -> inspector.inspect(jar), "META-INF/pi-plugin.json");
    }

    @Test
    void rejectsBundledApiClasses() throws IOException {
        Path jar = PluginJarFixture.withBundledApiClass(tempDir.resolve("bundled"));

        assertFailure(() -> inspector.inspect(jar), "plugin-api");
    }

    @Test
    void rejectsIncompatibleApiVersion() throws IOException {
        Path jar = PluginJarFixture.incompatibleApi(tempDir.resolve("incompatible"));

        assertFailure(() -> inspector.inspect(jar), DomainErrorCode.PLUGIN_INCOMPATIBLE,
                "主版本");
    }

    @Test
    void rejectsMissingMainClass() throws IOException {
        Path jar = PluginJarFixture.missingMainClass(tempDir.resolve("missing"));

        assertFailure(() -> inspector.inspect(jar), "入口类");
    }

    @Test
    void rejectsNonJarFile() throws IOException {
        Path file = Files.writeString(tempDir.resolve("not-a.jar"), "plain text");

        assertFailure(() -> inspector.inspect(file), "插件 jar");
    }

    private void assertFailure(ThrowingCallable call, String messagePart) {
        assertFailure(call, DomainErrorCode.PLUGIN_LOAD_FAILED, messagePart);
    }

    private void assertFailure(ThrowingCallable call, DomainErrorCode code, String messagePart) {
        assertThatThrownBy(call).isInstanceOfSatisfying(DomainException.class, error -> {
            assertThat(error.code()).isEqualTo(code);
            assertThat(error.getMessage()).contains(messagePart);
        });
    }
}
