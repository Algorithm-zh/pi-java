package com.piagent.plugin.api;

import java.util.Map;

/**
 * 插件工具调用。
 *
 * @param id 模型给出的调用 ID
 * @param name 完整工具名（{@code pluginId.toolName}）
 * @param arguments 参数，不可变
 */
public record PluginToolCall(String id, String name, Map<String, Object> arguments) {

    /**
     * 校验调用并冻结参数。
     *
     * @param id 调用 ID
     * @param name 完整工具名
     * @param arguments 参数
     */
    public PluginToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("调用 ID 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
    }
}
