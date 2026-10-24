package com.piagent.infrastructure.mcp;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 已初始化完成的 MCP 客户端。
 *
 * <p>刻意保持很小的契约：连接时发现工具，之后只负责调用。传输细节、JSON-RPC
 * 报文与重连策略都在实现内部，宿主只关心“有哪些工具”和“调用结果”。</p>
 */
public interface McpClient extends AutoCloseable {

    /**
     * 完成 initialize 握手并读取工具目录。
     *
     * @return 工具列表
     */
    List<McpToolInfo> connect();

    /**
     * 调用远程工具。
     *
     * @param remoteTool 远程工具名
     * @param arguments 参数
     * @param timeout 超时
     * @return 规范化结果
     */
    McpCallResult call(String remoteTool, Map<String, Object> arguments, Duration timeout);

    /**
     * 连接是否可用。
     *
     * @return true 表示可以直接调用
     */
    boolean isOpen();

    @Override
    void close();
}
