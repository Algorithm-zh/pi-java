package com.piagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.infrastructure.config.McpSettings;
import com.piagent.infrastructure.mcp.fixture.FakeMcpServer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * stdio 传输集成测试：真实启动假 MCP 服务器进程。
 */
class StdioMcpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpServerConnection connection;

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void discoversToolsAndCallsEcho() {
        McpServerConnection client = connect(Duration.ofSeconds(5));

        assertThat(client.isOpen()).isTrue();
        assertThat(client.call("echo", Map.of("text", "你好"), Duration.ofSeconds(5)).content())
                .isEqualTo("echo:你好");
    }

    @Test
    void mapsToolLevelErrors() {
        McpServerConnection client = connect(Duration.ofSeconds(5));

        McpCallResult result = client.call("fail", Map.of(), Duration.ofSeconds(5));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("boom");
        assertThat(result.details()).containsEntry("errorCode", "MCP_TOOL_ERROR");
    }

    @Test
    void timesOutSlowTool() {
        McpServerConnection client = connect(Duration.ofSeconds(5));

        assertThatThrownBy(() -> client.call("slow", Map.of(), Duration.ofMillis(300)))
                .isInstanceOfSatisfying(McpException.class,
                        error -> assertThat(error.errorCode()).isEqualTo("MCP_TIMEOUT"));
    }

    @Test
    void closeMarksConnectionClosed() {
        McpServerConnection client = connect(Duration.ofSeconds(5));

        client.close();

        assertThat(client.isOpen()).isFalse();
    }

    private McpServerConnection connect(Duration requestTimeout) {
        McpSettings.Server server = McpTestServers.stdio("fake", McpTestServers.javaCommand(),
                McpTestServers.fixtureArgs(FakeMcpServer.class), requestTimeout);
        connection = new McpServerConnection(server,
                new StdioMcpTransport(server, MAPPER), MAPPER);
        var tools = connection.connect();
        assertThat(tools).extracting(McpToolInfo::remoteName)
                .containsExactly("echo", "fail", "slow", "die");
        return connection;
    }
}
