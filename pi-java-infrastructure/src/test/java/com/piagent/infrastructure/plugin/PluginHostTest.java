package com.piagent.infrastructure.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.extension.model.PluginArtifact;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.model.PluginPackage;
import com.piagent.domain.extension.model.PluginRecord;
import com.piagent.domain.extension.model.PluginStatus;
import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.infrastructure.config.McpSettings;
import com.piagent.infrastructure.mcp.McpHost;
import com.piagent.infrastructure.plugin.support.PluginJarFixture;
import com.piagent.infrastructure.tool.BuiltInToolInvoker;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 插件宿主测试：加载、调用、停用、回滚与超时。
 */
class PluginHostTest {

    private static final Instant NOW = Instant.parse("2026-10-22T00:00:00Z");
    private static final PluginId HELLO = new PluginId("example.hello");
    private static final ToolName ECHO = new ToolName("example.hello.echo");

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private PluginJarInspector inspector;
    private RuntimeToolRegistry registry;
    private ExecutorService executor;
    private PluginHost host;

    @BeforeEach
    void setUp() {
        inspector = new PluginJarInspector(mapper);
        registry = new RuntimeToolRegistry(List.of(readOnlyDescriptor()));
        executor = Executors.newVirtualThreadPerTaskExecutor();
        host = new PluginHost(inspector, registry, mapper, executor);
    }

    @AfterEach
    void tearDown() {
        host.close();
        executor.shutdownNow();
    }

    @Test
    void loadsPluginAndInvokesTool() throws IOException {
        host.load(installation(PluginJarFixture.hello(tempDir.resolve("hello"))));

        ToolResult result = host.invoke(invocation("call-1", ECHO, Map.of("text", "hi")));

        assertThat(host.isLoaded(HELLO)).isTrue();
        assertThat(registry.find(ECHO)).isPresent();
        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("echo:hi");
        assertThat(result.details()).containsEntry("pluginId", "example.hello");
    }

    @Test
    void runtimeInvokerPrefersPluginTool() throws IOException {
        host.load(installation(PluginJarFixture.hello(tempDir.resolve("hello"))));
        try (BuiltInToolInvoker builtIn = new BuiltInToolInvoker(registry, Map.of())) {
            McpHost mcp = new McpHost(McpSettings.empty(), registry,
                    server -> {
                        throw new IllegalStateException("未配置 MCP 服务器");
                    }, mapper);
            RuntimeToolInvoker invoker = new RuntimeToolInvoker(builtIn, host, mcp);

            ToolResult result = invoker.invoke(invocation("call-2", ECHO, Map.of("text", "route")));

            assertThat(result.content()).isEqualTo("echo:route");
        }
    }

    @Test
    void unloadRemovesToolButKeepsRunningHandle() throws IOException {
        host.load(installation(PluginJarFixture.hello(tempDir.resolve("hello"))));

        host.unload(HELLO);

        assertThat(host.isLoaded(HELLO)).isFalse();
        assertThat(registry.find(ECHO)).isEmpty();
        ToolResult result = host.invoke(invocation("call-3", ECHO, Map.of("text", "old")));
        assertThat(result.content()).isEqualTo("echo:old");
    }

    @Test
    void failedStartRollsBackRegisteredTools() throws IOException {
        PluginInstallation installation =
                installation(PluginJarFixture.failingStart(tempDir.resolve("failing")));

        assertThatThrownBy(() -> host.load(installation))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo(DomainErrorCode.PLUGIN_LOAD_FAILED);
                    assertThat(error.getMessage()).contains("boom");
                });
        assertThat(host.isLoaded(new PluginId("example.fail"))).isFalse();
        assertThat(registry.find(new ToolName("example.fail.echo"))).isEmpty();
        assertThat(host.hasTool(new ToolName("example.fail.echo"))).isFalse();
    }

    @Test
    void invalidSchemaRollsBackPlugin() throws IOException {
        PluginInstallation installation =
                installation(PluginJarFixture.invalidSchema(tempDir.resolve("schema")));

        assertThatThrownBy(() -> host.load(installation))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo(DomainErrorCode.PLUGIN_LOAD_FAILED);
                    assertThat(error.getMessage()).contains("JSON");
                });
        assertThat(registry.find(new ToolName("example.badschema.broken"))).isEmpty();
    }

    @Test
    void rejectsInstallationThatDiffersFromJar() throws IOException {
        Path jar = PluginJarFixture.hello(tempDir.resolve("hello"));
        PluginPackage pkg = inspector.inspect(jar);
        PluginArtifact artifact = new PluginArtifact("9.9.9", pkg.apiVersion(),
                pkg.jarPath(), pkg.checksum(), null);
        PluginInstallation mismatch = PluginInstallation.reconstitute(new PluginRecord(
                pkg.id(), artifact, PluginStatus.INSTALLED, null, NOW, NOW));

        assertThatThrownBy(() -> host.load(mismatch))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_LOAD_FAILED));
        assertThat(host.isLoaded(HELLO)).isFalse();
    }

    @Test
    void rejectsLoadingSamePluginTwice() throws IOException {
        PluginInstallation installation =
                installation(PluginJarFixture.hello(tempDir.resolve("hello")));
        host.load(installation);

        assertThatThrownBy(() -> host.load(installation))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_INVALID_STATE));
    }

    @Test
    void timesOutSlowPluginTool() throws IOException {
        host.load(installation(PluginJarFixture.slow(tempDir.resolve("slow"))));

        ToolResult result = host.invoke(invocation("call-4",
                new ToolName("example.slow.sleep"), Map.of()));

        assertThat(result.error()).isTrue();
        assertThat(result.details()).containsEntry("errorCode", "TOOL_TIMEOUT");
    }

    @Test
    void closeClearsCatalogAndHandles() throws IOException {
        host.load(installation(PluginJarFixture.hello(tempDir.resolve("hello"))));

        host.close();

        assertThat(host.isLoaded(HELLO)).isFalse();
        assertThat(host.hasTool(ECHO)).isFalse();
        assertThat(registry.find(ECHO)).isEmpty();
    }

    private PluginInstallation installation(Path jar) {
        return PluginInstallation.install(inspector.inspect(jar), null, NOW);
    }

    private ToolInvocation invocation(String callId, ToolName tool, Map<String, Object> arguments) {
        return new ToolInvocation(new ToolCallId(callId), tool, arguments,
                SandboxPolicy.workspaceWritable(tempDir));
    }

    private ToolDescriptor readOnlyDescriptor() {
        return new ToolDescriptor(new ToolName("read"), "read a file", "{\"type\":\"object\"}",
                ToolRisk.LOW, ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }
}
