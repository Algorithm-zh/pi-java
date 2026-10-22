package com.piagent.plugin.api;

import java.util.Map;

/**
 * 插件工具结果。
 *
 * <p>失败不抛异常，而是返回 {@code error=true}，让模型看到原因并自行纠正。
 * {@code metadata} 只允许字符串键值，宿主会转成 UI 细节。</p>
 *
 * @param content 文本内容
 * @param error 是否失败
 * @param metadata 结构化细节
 */
public record PluginToolResult(String content, boolean error, Map<String, String> metadata) {

    /**
     * 校验结果并冻结细节。
     *
     * @param content 文本
     * @param error 是否失败
     * @param metadata 细节
     */
    public PluginToolResult {
        content = content == null ? "" : content;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    /**
     * 成功结果。
     *
     * @param content 文本
     * @return 结果
     */
    public static PluginToolResult success(String content) {
        return new PluginToolResult(content, false, Map.of());
    }

    /**
     * 失败结果。
     *
     * @param message 错误消息
     * @return 结果
     */
    public static PluginToolResult failure(String message) {
        return new PluginToolResult(message, true, Map.of());
    }
}
