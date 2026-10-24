package com.piagent.app.mcp.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 应用端到端测试用的最小 MCP stdio 服务器：只提供 {@code echo} 工具。
 */
public final class AppFakeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AppFakeMcpServer() {
    }

    /**
     * 进程入口。
     *
     * @param args 未使用
     * @throws Exception 读写失败
     */
    public static void main(String[] args) throws Exception {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            PrintWriter out = new PrintWriter(
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
            String line;
            while ((line = reader.readLine()) != null) {
                JsonNode request = MAPPER.readTree(line);
                if (!request.hasNonNull("id")) {
                    continue;
                }
                String method = request.path("method").asText();
                ObjectNode response = MAPPER.createObjectNode();
                response.put("jsonrpc", "2.0");
                response.set("id", request.get("id"));
                response.set("result", result(method, request.path("params")));
                out.println(MAPPER.writeValueAsString(response));
            }
        }
    }

    private static ObjectNode result(String method, JsonNode params) {
        ObjectNode result = MAPPER.createObjectNode();
        switch (method) {
            case "initialize" -> result.put("protocolVersion", "2025-06-18");
            case "tools/list" -> result.putArray("tools").add(MAPPER.createObjectNode()
                    .put("name", "echo").put("description", "回显文本")
                    .set("inputSchema", MAPPER.createObjectNode().put("type", "object")));
            case "tools/call" -> {
                result.put("isError", false);
                result.putArray("content").add(MAPPER.createObjectNode()
                        .put("type", "text").put("text", "echo:"
                                + params.path("arguments").path("text").asText()));
            }
            default -> result.put("ok", false);
        }
        return result;
    }
}
