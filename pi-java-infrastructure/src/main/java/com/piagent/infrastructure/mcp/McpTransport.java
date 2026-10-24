package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/**
 * MCP 传输层。
 *
 * <p>一次 {@code request} 对应一个 JSON-RPC 调用；实现必须在超时前返回与 id
 * 匹配的响应，无法完成时抛 {@link McpException}，不返回 null。通知没有响应。</p>
 */
public interface McpTransport extends AutoCloseable {

    /**
     * 发送请求并等待响应。
     *
     * @param id 请求 ID
     * @param method 方法
     * @param params 参数
     * @param timeout 超时
     * @return 完整 JSON-RPC 响应
     */
    JsonNode request(int id, String method, JsonNode params, Duration timeout);

    /**
     * 发送通知。
     *
     * @param method 方法
     * @param params 参数
     */
    void notify(String method, JsonNode params);

    /**
     * 连接是否可用。
     *
     * @return true 表示可以继续发送请求
     */
    boolean isOpen();

    @Override
    void close();
}
