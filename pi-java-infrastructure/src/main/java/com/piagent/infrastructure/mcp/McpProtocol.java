package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * JSON-RPC 2.0 报文构造与结果解析。
 *
 * <p>只覆盖 MCP 用到的子集：请求、通知、错误提取与 content 文本合并。
 * 协议错误统一抛 {@link McpException}，由调用器转换成工具错误结果。</p>
 */
public final class McpProtocol {

    /** 客户端声明的 MCP 协议版本。 */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** 客户端名称。 */
    public static final String CLIENT_NAME = "pi-java";

    /** 客户端版本。 */
    public static final String CLIENT_VERSION = "0.2.0-SNAPSHOT";

    private McpProtocol() {
    }

    /**
     * 构造请求。
     *
     * @param mapper JSON 映射器
     * @param id 请求 ID
     * @param method 方法
     * @param params 参数；可为 null
     * @return 请求报文
     */
    public static ObjectNode request(ObjectMapper mapper, int id, String method, JsonNode params) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", id);
        request.put("method", method);
        request.set("params", params == null ? mapper.createObjectNode() : params);
        return request;
    }

    /**
     * 构造通知（没有 id，不期待响应）。
     *
     * @param mapper JSON 映射器
     * @param method 方法
     * @param params 参数；可为 null
     * @return 通知报文
     */
    public static ObjectNode notification(ObjectMapper mapper, String method, JsonNode params) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", method);
        notification.set("params", params == null ? mapper.createObjectNode() : params);
        return notification;
    }

    /**
     * 提取 result，遇到 JSON-RPC error 时抛出协议异常。
     *
     * @param response 响应
     * @return result 节点
     */
    public static JsonNode result(JsonNode response) {
        if (response == null || !response.isObject()) {
            throw new McpException("MCP_PROTOCOL", "MCP 响应不是 JSON 对象");
        }
        JsonNode error = response.get("error");
        if (error != null && !error.isNull()) {
            String message = error.path("message").asText("未知 JSON-RPC 错误");
            throw new McpException("MCP_PROTOCOL", "MCP 错误: " + message);
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull()) {
            throw new McpException("MCP_PROTOCOL", "MCP 响应缺少 result");
        }
        return result;
    }

    /**
     * 合并 content 数组中的文本；非文本内容用占位符表示。
     *
     * @param result tools/call 的 result
     * @return 文本
     */
    public static String contentText(JsonNode result) {
        JsonNode content = result.path("content");
        if (!content.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode item : content) {
            String piece = switch (item.path("type").asText("")) {
                case "text" -> item.path("text").asText("");
                case "image" -> "[图片 " + item.path("mimeType").asText("unknown") + "]";
                case "resource" -> "[资源 " + item.path("resource").path("uri").asText("unknown") + "]";
                case "audio" -> "[音频 " + item.path("mimeType").asText("unknown") + "]";
                default -> "[非文本内容 " + item.path("type").asText("unknown") + "]";
            };
            if (piece.isEmpty()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(piece);
        }
        return text.toString();
    }
}
