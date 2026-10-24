package com.piagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.infrastructure.config.McpSettings;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Streamable HTTP 传输测试：JSON 与 SSE 响应、会话头与 DELETE 关闭。
 */
class HttpMcpTransportTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicReference<String> sessionOnToolsList = new AtomicReference<>();
    private final AtomicReference<String> deleteSession = new AtomicReference<>();
    private final AtomicReference<String> acceptHeader = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.createContext("/bad", exchange -> {
            byte[] body = "服务不可用".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void discoversOverSseAndCallsOverJson() {
        HttpMcpTransport transport = new HttpMcpTransport(serverConfig(), MAPPER);
        McpServerConnection client = new McpServerConnection(serverConfig(), transport, MAPPER);

        assertThat(client.connect()).extracting(McpToolInfo::remoteName)
                .containsExactly("echo");
        assertThat(client.call("echo", Map.of("text", "hi"), Duration.ofSeconds(3)).content())
                .isEqualTo("echo:hi");
        assertThat(sessionOnToolsList.get()).isEqualTo("session-1");
        assertThat(acceptHeader.get()).contains("text/event-stream");

        client.close();
        assertThat(deleteSession.get()).isEqualTo("session-1");
        assertThat(client.isOpen()).isFalse();
    }

    @Test
    void mapsHttpErrorsToTransportFailure() {
        McpSettings.Server bad = McpTestServers.http("bad", "http://127.0.0.1:"
                + server.getAddress().getPort() + "/bad");
        HttpMcpTransport transport = new HttpMcpTransport(bad, MAPPER);

        assertThatThrownBy(() -> transport.request(1, "initialize",
                MAPPER.createObjectNode(), Duration.ofSeconds(3)))
                .isInstanceOfSatisfying(McpException.class, error -> {
                    assertThat(error.errorCode()).isEqualTo("MCP_TRANSPORT");
                    assertThat(error.getMessage()).contains("500");
                });
    }

    private McpSettings.Server serverConfig() {
        return McpTestServers.http("docs", "http://127.0.0.1:"
                + server.getAddress().getPort() + "/mcp");
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deleteSession.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                exchange.sendResponseHeaders(204, -1);
                return;
            }
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            JsonNode request = MAPPER.readTree(exchange.getRequestBody().readAllBytes());
            String method = request.path("method").asText();
            if ("initialize".equals(method)) {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                sendJson(exchange, response(request, MAPPER.createObjectNode()
                        .put("protocolVersion", "2025-06-18")));
                return;
            }
            if ("tools/list".equals(method)) {
                sessionOnToolsList.set(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                sendEvent(exchange, response(request, toolsResult()));
                return;
            }
            ObjectNode result = MAPPER.createObjectNode();
            result.put("isError", false);
            result.putArray("content").add(MAPPER.createObjectNode()
                    .put("type", "text").put("text", "echo:"
                            + request.path("params").path("arguments").path("text").asText()));
            sendJson(exchange, response(request, result));
        }
    }

    private static ObjectNode response(JsonNode request, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        return response;
    }

    private static ObjectNode toolsResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.putArray("tools").add(MAPPER.createObjectNode()
                .put("name", "echo").put("description", "回显")
                .set("inputSchema", MAPPER.createObjectNode().put("type", "object")));
        return result;
    }

    private static void sendJson(HttpExchange exchange, JsonNode payload) throws IOException {
        byte[] body = MAPPER.writeValueAsBytes(payload);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void sendEvent(HttpExchange exchange, JsonNode payload) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            String body = "event: message\ndata: "
                    + MAPPER.writeValueAsString(MAPPER.createObjectNode()
                            .put("jsonrpc", "2.0").put("method", "notifications/message"))
                    + "\n\nevent: message\ndata: " + MAPPER.writeValueAsString(payload)
                    + "\n\n";
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
