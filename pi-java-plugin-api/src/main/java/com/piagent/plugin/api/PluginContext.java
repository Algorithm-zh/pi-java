package com.piagent.plugin.api;

/**
 * 插件运行时上下文。
 *
 * <p>v0.1 只开放工具注册与日志。命令、hook、拦截器与 prompt 片段会在消费它们的
 * 里程碑中以新增方法的方式加入，保持 API 主版本不变。</p>
 */
public interface PluginContext {

    /**
     * 注册工具。
     *
     * <p>只能在 {@link PiPlugin#start(PluginContext)} 期间调用；完整工具名由宿主
     * 生成为 {@code pluginId.toolName}。</p>
     *
     * @param tool 工具实现
     * @return 注册句柄
     */
    Registration registerTool(PluginTool tool);

    /**
     * 插件日志器，自动带插件前缀。
     *
     * @return 日志器
     */
    PluginLogger log();
}
