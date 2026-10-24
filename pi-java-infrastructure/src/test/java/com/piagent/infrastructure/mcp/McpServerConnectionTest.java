package com.piagent.infrastructure.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 协议客户端测试：分页、初始化通知与内容类型转换。
 */
class McpServerConnectionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void paginatesToolsAndSendsInitializedNotification() {
        RecordingTransport transport = new RecordingTransport();
        transport.responses.add(response(MAPPER.createObjectNode()
                .put("protocolVersion", "2025-06-18")));
        ObjectNode pageOne = MAPPER.createObjectNode();
        pageOne.putArray("tools").add(tool("alpha", "第一个"));
        pageOne.put("nextCursor", "cursor-2");
        transport.responses.add(response(pageOne));
        ObjectNode pageTwo = MAPPER.createObjectNode();
        pageTwo.putArray("tools").add(tool("beta", "第二个"));
        transport.responses.add(response(pageTwo));

        McpServerConnection client = new McpServerConnection(
                McpTestServers.http("docs", "http://127.0.0.1:1/mcp"), transport, MAPPER);
        List<McpToolInfo> tools = client.connect();

        assertThat(tools).extracting(McpToolInfo::remoteName)
                .containsExactly("alpha", "beta");
        assertThat(transport.notifications).containsExactly("notifications/initialized");
        assertThat(transport.methods)
                .containsExactly("initialize", "tools/list", "tools/list");
        assertThat(transport.requests.get(2).path("params").path("cursor").asText())
                .isEqualTo("cursor-2");
    }

    @Test
    void convertsContentAndStructuredOutput() {
        RecordingTransport transport = new RecordingTransport();
        ObjectNode result = MAPPER.createObjectNode();
        result.put("isError", false);
        result.putArray("content")
                .add(MAPPER.createObjectNode().put("type", "text").put("text", "第一行"))
                .add(MAPPER.createObjectNode().put("type", "image").put("mimeType", "image/png"));
        result.putObject("structuredContent").put("count", 2);
        transport.responses.add(response(result));
        McpServerConnection client = new McpServerConnection(
                McpTestServers.http("docs", "http://127.0.0.1:1/mcp"), transport, MAPPER);

        McpCallResult call = client.call("render", Map.of("id", 1), Duration.ofSeconds(3));

        assertThat(call.error()).isFalse();
        assertThat(call.content()).isEqualTo("第一行\n[图片 image/png]");
        assertThat(call.details()).containsEntry("server", "docs")
                .containsEntry("mcpTool", "render");
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) call.details()
                .get("structuredContent");
        assertThat(structured).containsEntry("count", 2);
    }

    private static JsonNode response(JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("result", result);
        return response;
    }

    private static ObjectNode tool(String name, String description) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        tool.putObject("inputSchema").put("type", "object");
        return tool;
    }

    /**
     * 按顺序返回响应的传输。
     */
    private static final class RecordingTransport implements McpTransport {

        private final Deque<JsonNode> responses = new ArrayDeque<>();
        private final List<String> methods = new ArrayList<>();
        private final List<JsonNode> requests = new ArrayList<>();
        private final List<String> notifications = new ArrayList<>();

        @Override
        public JsonNode request(int id, String method, JsonNode params, Duration timeout) {
            methods.add(method);
            ObjectNode request = MAPPER.createObjectNode();
            request.put("id", id);
            request.set("params", params);
            requests.add(request);
            return responses.removeFirst();
        }

        @Override
        public void notify(String method, JsonNode params) {
            notifications.add(method);
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
        }
    }
}
