package com.piagent.infrastructure.plugin;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.plugin.api.PluginContext;
import com.piagent.plugin.api.PluginLogger;
import com.piagent.plugin.api.PluginTool;
import com.piagent.plugin.api.Registration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 插件启动期上下文。
 *
 * <p>注册项先收集在本地，只有在 {@code start} 正常返回后才发布到工具目录；
 * 启动失败时直接丢弃，保证不留下半注册状态。工具句柄关闭后从集合中移除。</p>
 */
final class PluginContextImpl implements PluginContext {

    private final PluginId pluginId;
    private final PluginLogger logger;
    private final List<PluginTool> tools = new ArrayList<>();
    private boolean closed;

    PluginContextImpl(PluginId pluginId, PluginLogger logger) {
        this.pluginId = pluginId;
        this.logger = logger;
    }

    @Override
    public synchronized Registration registerTool(PluginTool tool) {
        Objects.requireNonNull(tool, "插件工具不能为空");
        if (closed) {
            throw new IllegalStateException("插件上下文已关闭: " + pluginId);
        }
        tools.add(tool);
        return () -> removeTool(tool);
    }

    @Override
    public PluginLogger log() {
        return logger;
    }

    synchronized List<PluginTool> tools() {
        return List.copyOf(tools);
    }

    synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        tools.clear();
    }

    private synchronized void removeTool(PluginTool tool) {
        tools.remove(tool);
    }
}
