package com.piagent.infrastructure.mcp;

import com.piagent.infrastructure.config.McpSettings;

/**
 * 按配置创建 MCP 客户端。
 *
 * <p>抽出工厂是为了让宿主可以在断线后重建客户端，也让单元测试可以注入
 * 内存实现而不启动真实进程或 HTTP 服务。</p>
 */
@FunctionalInterface
public interface McpClientFactory {

    /**
     * 创建尚未初始化的客户端。
     *
     * @param server 服务器配置
     * @return 客户端
     */
    McpClient create(McpSettings.Server server);
}
