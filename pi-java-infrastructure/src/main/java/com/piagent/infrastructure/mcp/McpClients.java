package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.infrastructure.config.McpSettings;
import java.util.Objects;

/**
 * 默认客户端工厂：按传输类型选择 stdio 或 HTTP。
 */
public final class McpClients {

    private McpClients() {
    }

    /**
     * 创建使用真实传输的工厂。
     *
     * @param mapper JSON 映射器
     * @return 工厂
     */
    public static McpClientFactory withDefaults(ObjectMapper mapper) {
        Objects.requireNonNull(mapper, "mapper 不能为空");
        return server -> new McpServerConnection(server, transport(server, mapper), mapper);
    }

    private static McpTransport transport(McpSettings.Server server, ObjectMapper mapper) {
        return switch (server.kind()) {
            case STDIO -> new StdioMcpTransport(server, mapper);
            case HTTP -> new HttpMcpTransport(server, mapper);
        };
    }
}
