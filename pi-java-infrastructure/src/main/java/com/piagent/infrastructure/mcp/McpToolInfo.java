package com.piagent.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP {@code tools/list} 返回的远程工具。
 *
 * @param remoteName 服务器上的原始工具名
 * @param description 描述；已由客户端补全
 * @param schema 参数 JSON Schema，保证是对象
 */
public record McpToolInfo(String remoteName, String description, JsonNode schema) {
}
