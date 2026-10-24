package com.piagent.infrastructure.mcp;

import com.piagent.infrastructure.config.McpSettings;
import java.util.Objects;

/**
 * 一个 MCP 服务器的运行时状态。
 *
 * <p>只在传输报告不可用时重连；重连后仍沿用启动时发现的工具定义，远程目录
 * 变化需要重启进程才生效。这样 run 内的工具快照与调用目标始终一致。</p>
 */
final class McpServerRuntime {

    private final McpSettings.Server server;
    private final McpClientFactory factory;
    private volatile McpClient client;

    McpServerRuntime(McpSettings.Server server, McpClient client, McpClientFactory factory) {
        this.server = Objects.requireNonNull(server, "server 不能为空");
        this.client = Objects.requireNonNull(client, "client 不能为空");
        this.factory = Objects.requireNonNull(factory, "factory 不能为空");
    }

    /**
     * 返回可直接调用的客户端；连接已断开时重连一次。
     *
     * @return 客户端
     */
    synchronized McpClient clientForCall() {
        McpClient current = client;
        if (current.isOpen()) {
            return current;
        }
        closeQuietly(current);
        McpClient fresh = factory.create(server);
        try {
            fresh.connect();
        } catch (RuntimeException error) {
            closeQuietly(fresh);
            throw error;
        }
        client = fresh;
        return fresh;
    }

    String serverName() {
        return server.name();
    }

    void close() {
        closeQuietly(client);
    }

    private void closeQuietly(McpClient target) {
        try {
            target.close();
        } catch (RuntimeException error) {
            System.getLogger(McpServerRuntime.class.getName())
                    .log(System.Logger.Level.DEBUG, "关闭 MCP 客户端失败: " + error.getMessage());
        }
    }
}
