package com.piagent.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.infrastructure.mcp.McpClients;
import com.piagent.infrastructure.mcp.McpHost;
import com.piagent.infrastructure.plugin.RuntimeToolRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 客户端装配。
 *
 * <p>宿主在 Spring 容器中单例存在，但不会自动连接：连接动作由应用启动器触发，
 * 这样连接失败只影响 MCP 工具，不阻断应用启动。所有服务器共享同一个
 * {@link RuntimeToolRegistry}，因此 MCP 工具与内置/插件工具走同一套冲突检查。</p>
 */
@Configuration
@EnableConfigurationProperties(McpSettings.class)
public class McpConfiguration {

    /**
     * MCP 宿主。
     *
     * @param settings MCP 配置
     * @param registry 运行时工具目录
     * @param mapper JSON 映射器
     * @return 宿主
     */
    @Bean(destroyMethod = "close")
    public McpHost mcpHost(McpSettings settings, RuntimeToolRegistry registry,
            ObjectMapper mapper) {
        return new McpHost(settings, registry, McpClients.withDefaults(mapper), mapper);
    }
}
