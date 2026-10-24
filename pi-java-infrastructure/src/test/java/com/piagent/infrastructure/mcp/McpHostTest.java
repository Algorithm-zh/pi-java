package com.piagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.infrastructure.config.McpSettings;
import com.piagent.infrastructure.plugin.RuntimeToolRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * MCP 宿主测试：注册、调用、断线重连、超时映射、冲突与卸载。
 */
class McpHostTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuntimeToolRegistry registry;
    private final List<FakeClient> clients = new ArrayList<>();
    private McpHost host;

    @BeforeEach
    void setUp() {
        registry = new RuntimeToolRegistry(List.of(descriptor("read", ToolRisk.LOW)));
    }

    @Test
    void registersNamespacedToolsWithRiskAndSequentialMode() {
        start(server("fs", McpSettings.Risks.defaults()));

        ToolDescriptor echo = registry.require(new ToolName("fs.echo"));
        assertThat(echo.risk()).isEqualTo(ToolRisk.MEDIUM);
        assertThat(echo.executionMode()).isEqualTo(ExecutionMode.SEQUENTIAL);
        assertThat(echo.timeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(registry.require(new ToolName("fs.write_file")).risk())
                .isEqualTo(ToolRisk.HIGH);
        assertThat(host.hasTool(new ToolName("fs.echo"))).isTrue();
    }

    @Test
    void invokesRemoteToolThroughAdapter() {
        start(server("fs", McpSettings.Risks.defaults()));

        ToolResult result = host.invoke(invocation("fs.echo", Map.of("text", "hi")));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("client-0:echo:hi");
        assertThat(result.details()).containsEntry("server", "fs")
                .containsEntry("mcpTool", "echo");
    }

    @Test
    void reconnectsOnceWhenTransportIsClosed() {
        start(server("fs", McpSettings.Risks.defaults()));
        clients.get(0).open = false;

        ToolResult result = host.invoke(invocation("fs.echo", Map.of("text", "again")));

        assertThat(clients).hasSize(2);
        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("client-1:echo:again");
    }

    @Test
    void mapsTransportFailureToToolError() {
        start(server("fs", McpSettings.Risks.defaults()));
        clients.get(0).failure = new McpException("MCP_TIMEOUT", "MCP 工具执行超时");

        ToolResult result = host.invoke(invocation("fs.echo", Map.of()));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("超时");
        assertThat(result.details()).containsEntry("errorCode", "MCP_TIMEOUT");
    }

    @Test
    void isolatedServerFailureDoesNotBreakStartup() {
        registry = new RuntimeToolRegistry(List.of(descriptor("fs.echo", ToolRisk.LOW)));

        int loaded = start(server("fs", McpSettings.Risks.defaults()));

        assertThat(loaded).isZero();
        assertThat(host.serverCount()).isZero();
        assertThat(registry.require(new ToolName("fs.echo")).description())
                .isEqualTo("内置工具");
    }

    @Test
    void closeRemovesMcpTools() {
        start(server("fs", McpSettings.Risks.defaults()));

        host.close();

        assertThat(registry.find(new ToolName("fs.echo"))).isEmpty();
        assertThat(host.hasTool(new ToolName("fs.echo"))).isFalse();
    }

    private int start(McpSettings.Server server) {
        AtomicInteger sequence = new AtomicInteger();
        McpClientFactory factory = ignored -> {
            FakeClient client = new FakeClient(sequence.getAndIncrement(),
                    ignored.name());
            clients.add(client);
            return client;
        };
        host = new McpHost(new McpSettings(List.of(server)), registry, factory, MAPPER);
        return host.start();
    }

    private static McpSettings.Server server(String name, McpSettings.Risks risks) {
        return new McpSettings.Server(name, "stdio",
                new McpSettings.Stdio("unused", List.of(), Map.of()), null,
                new McpSettings.Timeouts(Duration.ofSeconds(5), Duration.ofSeconds(5)), risks);
    }

    private static ToolInvocation invocation(String tool, Map<String, Object> arguments) {
        return new ToolInvocation(new ToolCallId("call-" + tool), new ToolName(tool),
                arguments, SandboxPolicy.readOnly(Path.of(".")));
    }

    private static ToolDescriptor descriptor(String name, ToolRisk risk) {
        return new ToolDescriptor(new ToolName(name), "内置工具", "{\"type\":\"object\"}",
                risk, ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }

    /**
     * 内存客户端：记录连接次数，可模拟关闭与失败。
     */
    private static final class FakeClient implements McpClient {

        private final int sequence;
        private final String serverName;
        private volatile boolean open = true;
        private volatile RuntimeException failure;

        private FakeClient(int sequence, String serverName) {
            this.sequence = sequence;
            this.serverName = serverName;
        }

        @Override
        public List<McpToolInfo> connect() {
            return List.of(
                    new McpToolInfo("echo", "回显",
                            MAPPER.createObjectNode().put("type", "object")),
                    new McpToolInfo("write_file", "写文件",
                            MAPPER.createObjectNode().put("type", "object")));
        }

        @Override
        public McpCallResult call(String remoteTool, Map<String, Object> arguments,
                Duration timeout) {
            if (failure != null) {
                throw failure;
            }
            if (!open) {
                throw new McpException("MCP_TRANSPORT", "连接已关闭");
            }
            return new McpCallResult("client-" + sequence + ":" + remoteTool + ":"
                    + arguments.getOrDefault("text", ""), false,
                    Map.of("server", serverName, "mcpTool", remoteTool));
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
