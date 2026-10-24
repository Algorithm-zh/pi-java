package com.piagent.infrastructure.mcp;

import java.util.Map;

/**
 * MCP {@code tools/call} 的规范化结果。
 *
 * @param content 给模型看的文本
 * @param error 是否是工具级错误
 * @param details 结构化细节
 */
public record McpCallResult(String content, boolean error, Map<String, Object> details) {

    /**
     * 冻结细节。
     *
     * @param content 文本
     * @param error 是否错误
     * @param details 细节
     */
    public McpCallResult {
        content = content == null ? "" : content;
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
