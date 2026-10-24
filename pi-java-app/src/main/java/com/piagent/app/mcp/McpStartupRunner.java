package com.piagent.app.mcp;

import com.piagent.infrastructure.mcp.McpHost;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动时连接 MCP 服务器并注册工具。
 *
 * <p>单个服务器失败由宿主记录告警并跳过，不影响应用启动；工具注册完成后，
 * 后续新建的 run 会在自己的工具快照中看到这些工具。</p>
 */
@Component
public class McpStartupRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(McpStartupRunner.class);

    private final McpHost host;

    /**
     * 创建启动器。
     *
     * @param host MCP 宿主
     */
    public McpStartupRunner(McpHost host) {
        this.host = host;
    }

    @Override
    public void run(ApplicationArguments args) {
        int started = host.start();
        if (started > 0) {
            LOG.info("已连接 {} 个 MCP 服务器", started);
        }
    }
}
