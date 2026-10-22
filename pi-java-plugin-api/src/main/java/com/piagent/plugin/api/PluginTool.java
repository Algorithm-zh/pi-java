package com.piagent.plugin.api;

/**
 * 插件工具。
 *
 * <p>实现必须无状态或自行保证线程安全；宿主会在不同虚拟线程上并发调用
 * 标记为 {@code parallelSafe} 的工具。</p>
 */
public interface PluginTool {

    /**
     * 工具定义。
     *
     * @return 定义
     */
    ToolSpec spec();

    /**
     * 执行工具。
     *
     * @param call 调用
     * @param context 执行上下文
     * @return 结果
     * @throws Exception 执行失败；宿主会转换为错误结果
     */
    PluginToolResult execute(PluginToolCall call, PluginToolContext context) throws Exception;
}
