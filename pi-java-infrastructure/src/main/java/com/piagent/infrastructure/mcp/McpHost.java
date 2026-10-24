package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.infrastructure.config.McpSettings;
import com.piagent.infrastructure.plugin.RuntimeToolRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 宿主：连接配置中的服务器，把远程工具注册进运行时目录，并分派调用。
 *
 * <p>启动按服务器隔离：某个服务器连接或注册失败只记录告警，其余服务器继续加载，
 * 应用始终可以启动。所有工具名都带服务器前缀（{@code <server>.<tool>}），
 * 与内置工具、插件工具在同一目录中做冲突检查。</p>
 */
public final class McpHost implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(McpHost.class.getName());

    private final McpSettings settings;
    private final RuntimeToolRegistry registry;
    private final McpClientFactory clients;
    private final ObjectMapper mapper;
    private final Map<String, McpServerRuntime> servers = new LinkedHashMap<>();
    private final Map<ToolName, McpToolAdapter> adapters = new ConcurrentHashMap<>();
    private boolean started;
    private boolean closed;

    /**
     * 创建宿主。
     *
     * @param settings MCP 配置
     * @param registry 运行时工具目录
     * @param clients 客户端工厂
     * @param mapper JSON 映射器
     */
    public McpHost(McpSettings settings, RuntimeToolRegistry registry, McpClientFactory clients,
            ObjectMapper mapper) {
        this.settings = settings == null ? McpSettings.empty() : settings;
        this.registry = Objects.requireNonNull(registry, "registry 不能为空");
        this.clients = Objects.requireNonNull(clients, "clients 不能为空");
        this.mapper = Objects.requireNonNull(mapper, "mapper 不能为空");
    }

    /**
     * 连接全部服务器并注册工具。
     *
     * @return 成功加载的服务器数量
     */
    public synchronized int start() {
        if (closed) {
            return 0;
        }
        if (started) {
            return servers.size();
        }
        started = true;
        int loaded = 0;
        for (McpSettings.Server server : settings.servers()) {
            if (startServer(server)) {
                loaded++;
            }
        }
        return loaded;
    }

    /**
     * 已加载的服务器数量。
     *
     * @return 数量
     */
    public synchronized int serverCount() {
        return servers.size();
    }

    /**
     * 工具是否由 MCP 提供。
     *
     * @param name 工具名
     * @return true 表示存在
     */
    public boolean hasTool(ToolName name) {
        return adapters.containsKey(name);
    }

    /**
     * 执行 MCP 工具。
     *
     * @param invocation 调用
     * @return 结果
     */
    public ToolResult invoke(ToolInvocation invocation) {
        McpToolAdapter adapter = adapters.get(invocation.tool());
        if (adapter == null) {
            throw new DomainException(DomainErrorCode.TOOL_NOT_FOUND,
                    "MCP 工具未加载: " + invocation.tool());
        }
        return adapter.invoke(invocation);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (McpServerRuntime runtime : servers.values()) {
            runtime.close();
            registry.removeMcp(runtime.serverName());
        }
        servers.clear();
        adapters.clear();
    }

    private boolean startServer(McpSettings.Server server) {
        McpClient client = null;
        try {
            client = clients.create(server);
            List<McpToolInfo> tools = client.connect();
            McpServerRuntime runtime = new McpServerRuntime(server, client, clients);
            McpRiskMapper risks = new McpRiskMapper(server.risks());
            List<McpToolAdapter> loaded = buildAdapters(server, tools, risks, runtime);
            registry.replaceMcp(server.name(), loaded.stream()
                    .map(McpToolAdapter::descriptor).toList());
            servers.put(server.name(), runtime);
            loaded.forEach(adapter -> adapters.put(adapter.descriptor().name(), adapter));
            LOG.log(System.Logger.Level.INFO, "MCP 服务器 {0} 已连接，注册 {1} 个工具",
                    server.name(), loaded.size());
            return true;
        } catch (RuntimeException error) {
            closeQuietly(client);
            LOG.log(System.Logger.Level.WARNING,
                    "MCP 服务器 " + server.name() + " 启动失败: " + message(error), error);
            return false;
        }
    }

    private List<McpToolAdapter> buildAdapters(McpSettings.Server server, List<McpToolInfo> tools,
            McpRiskMapper risks, McpServerRuntime runtime) {
        Map<String, McpToolAdapter> byName = new LinkedHashMap<>();
        for (McpToolInfo tool : tools) {
            try {
                McpToolAdapter adapter = new McpToolAdapter(server, tool, risks, runtime, mapper);
                String name = adapter.descriptor().name().value();
                if (byName.putIfAbsent(name, adapter) != null) {
                    LOG.log(System.Logger.Level.WARNING,
                            "跳过重复的 MCP 工具: " + name);
                }
            } catch (RuntimeException error) {
                LOG.log(System.Logger.Level.WARNING,
                        "跳过 MCP 工具 " + tool.remoteName() + ": " + message(error));
            }
        }
        return new ArrayList<>(byName.values());
    }

    private void closeQuietly(McpClient client) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException error) {
            LOG.log(System.Logger.Level.DEBUG, "关闭 MCP 客户端失败: " + error.getMessage());
        }
    }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
