package com.piagent.infrastructure.mcp.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

/**
 * 测试用 MCP stdio 服务器：换行分隔 JSON，支持 initialize、tools/list 与 tools/call。
 *
 * <p>工具：{@code echo}（回显）、{@code fail}（工具级错误）、{@code slow}（延迟 2 秒）、
 * {@code die}（返回后退出进程，用于验证断线重连）。每个响应前先发一条通知，
 * 验证客户端会忽略非匹配消息。</p>
 */
public final class FakeMcpServer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private FakeMcpServer() {
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
                int id = request.get("id").asInt();
                String method = request.path("method").asText();
                if ("tools/call".equals(method)) {
                    handleCall(id, request.path("params"), out);
                } else {
                    send(out, notification(), response(id, method));
                }
            }
        }
    }

    private static void handleCall(int id, JsonNode params, PrintWriter out) throws Exception {
        String name = params.path("name").asText();
        String text = params.path("arguments").path("text").asText("");
        if ("slow".equals(name)) {
            Thread.sleep(2_000);
        }
        ObjectNode result = MAPPER.createObjectNode();
        if ("fail".equals(name)) {
            result.put("isError", true);
            ((ArrayNode) result.putArray("content")).add(textContent("boom"));
        } else {
            result.put("isError", false);
            ((ArrayNode) result.putArray("content")).add(textContent("echo:" + text));
        }
        send(out, notification(), response(id, result));
        if ("die".equals(name)) {
            out.flush();
            Thread.sleep(100);
            System.exit(0);
        }
    }

    private static ObjectNode response(int id, String method) {
        ObjectNode result = switch (method) {
            case "initialize" -> initializeResult();
            case "tools/list" -> toolsResult();
            default -> MAPPER.createObjectNode();
        };
        return response(id, result);
    }

    private static ObjectNode response(int id, JsonNode result) {
        ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.set("result", result);
        return response;
    }

    private static ObjectNode notification() {
        ObjectNode notification = MAPPER.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/message");
        notification.set("params", MAPPER.createObjectNode());
        return notification;
    }

    private static ObjectNode initializeResult() {
        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", "2025-06-18");
        ObjectNode capabilities = MAPPER.createObjectNode();
        capabilities.putObject("tools").put("listChanged", true);
        result.set("capabilities", capabilities);
        result.set("serverInfo", MAPPER.createObjectNode()
                .put("name", "fake-mcp").put("version", "1.0"));
        return result;
    }

    private static ObjectNode toolsResult() {
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        tools.add(tool("echo", "回显文本"));
        tools.add(tool("fail", "总是返回工具错误"));
        tools.add(tool("slow", "延迟 2 秒"));
        tools.add(tool("die", "返回后退出进程"));
        return result;
    }

    private static ObjectNode tool(String name, String description) {
        ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description);
        ObjectNode schema = tool.putObject("inputSchema");
        schema.put("type", "object");
        schema.putObject("properties").putObject("text").put("type", "string");
        return tool;
    }

    private static ObjectNode textContent(String text) {
        ObjectNode content = MAPPER.createObjectNode();
        content.put("type", "text");
        content.put("text", text);
        return content;
    }

    private static void send(PrintWriter out, ObjectNode... messages) throws Exception {
        for (ObjectNode message : messages) {
            out.println(MAPPER.writeValueAsString(message));
        }
    }
}
