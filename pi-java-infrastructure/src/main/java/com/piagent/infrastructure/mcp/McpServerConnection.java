package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.infrastructure.config.McpSettings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MCP 协议客户端：初始化握手、工具发现与工具调用。
 *
 * <p>工具发现支持 {@code nextCursor} 分页，最多读取 50 页，避免服务端分页错误
 * 导致无限循环。描述为空时补默认文本；参数 schema 不是 JSON 对象的工具直接跳过，
 * 因为 {@code ToolDescriptor} 与 LLM 工具协议都要求对象 schema。</p>
 */
public final class McpServerConnection implements McpClient {

    private static final System.Logger LOG =
            System.getLogger(McpServerConnection.class.getName());
    private static final int MAX_DESCRIPTION = 1_000;
    private static final int MAX_PAGES = 50;

    private final McpSettings.Server server;
    private final McpTransport transport;
    private final ObjectMapper mapper;
    private final AtomicInteger nextId = new AtomicInteger(1);

    /**
     * 创建客户端。
     *
     * @param server 服务器配置
     * @param transport 传输
     * @param mapper JSON 映射器
     */
    public McpServerConnection(McpSettings.Server server, McpTransport transport,
            ObjectMapper mapper) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.transport = Objects.requireNonNull(transport, "transport 不能为空");
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    }

    @Override
    public List<McpToolInfo> connect() {
        initialize();
        return listTools();
    }

    @Override
    public McpCallResult call(String remoteTool, Map<String, Object> arguments, Duration timeout) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", remoteTool);
        params.set("arguments", mapper.valueToTree(arguments));
        JsonNode result = McpProtocol.result(request("tools/call", params, timeout));
        boolean error = result.path("isError").asBoolean(false);
        String content = McpProtocol.contentText(result);
        if (content.isBlank()) {
            content = error ? "MCP 工具返回错误（无文本内容）" : "MCP 工具没有返回文本内容";
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("server", server.name());
        details.put("mcpTool", remoteTool);
        if (result.path("structuredContent").isObject()) {
            details.put("structuredContent", mapper.convertValue(
                    result.get("structuredContent"), new TypeReference<Map<String, Object>>() { }));
        }
        if (error) {
            details.put("errorCode", "MCP_TOOL_ERROR");
        }
        return new McpCallResult(content, error, details);
    }

    @Override
    public boolean isOpen() {
        return transport.isOpen();
    }

    @Override
    public void close() {
        transport.close();
    }

    private void initialize() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", McpProtocol.PROTOCOL_VERSION);
        params.set("capabilities", mapper.createObjectNode());
        ObjectNode info = params.putObject("clientInfo");
        info.put("name", McpProtocol.CLIENT_NAME);
        info.put("version", McpProtocol.CLIENT_VERSION);
        JsonNode result = McpProtocol.result(request("initialize", params,
                server.timeouts().discovery()));
        String version = result.path("protocolVersion").asText("");
        if (version.isBlank()) {
            throw new McpException("MCP_PROTOCOL",
                    "MCP 服务器 " + server.name() + " 的 initialize 响应缺少 protocolVersion");
        }
        transport.notify("notifications/initialized", mapper.createObjectNode());
    }

    private List<McpToolInfo> listTools() {
        List<McpToolInfo> tools = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            ObjectNode params = mapper.createObjectNode();
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            JsonNode result = McpProtocol.result(request("tools/list", params,
                    server.timeouts().discovery()));
            readPage(result, tools);
            cursor = result.path("nextCursor").asText(null);
            pages++;
        } while (cursor != null && !cursor.isBlank() && pages < MAX_PAGES);
        return tools;
    }

    private void readPage(JsonNode result, List<McpToolInfo> tools) {
        JsonNode list = result.path("tools");
        if (!list.isArray()) {
            return;
        }
        for (JsonNode tool : list) {
            McpToolInfo info = readTool(tool);
            if (info != null) {
                tools.add(info);
            }
        }
    }

    private McpToolInfo readTool(JsonNode tool) {
        String name = tool.path("name").asText("");
        if (name.isBlank()) {
            return null;
        }
        JsonNode schema = tool.path("inputSchema");
        if (!schema.isObject()) {
            LOG.log(System.Logger.Level.WARNING,
                    "跳过 MCP 工具 " + name + "：inputSchema 不是 JSON 对象");
            return null;
        }
        String description = tool.path("description").asText("");
        if (description.isBlank()) {
            description = "MCP 工具 " + name;
        }
        if (description.length() > MAX_DESCRIPTION) {
            description = description.substring(0, MAX_DESCRIPTION);
        }
        return new McpToolInfo(name, description, schema);
    }

    private JsonNode request(String method, JsonNode params, Duration timeout) {
        return transport.request(nextId.getAndIncrement(), method, params, timeout);
    }
}
