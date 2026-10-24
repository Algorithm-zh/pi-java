package com.piagent.infrastructure.plugin;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.service.PluginCompatibilityChecker;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.port.ToolRegistry;
import com.piagent.domain.tool.service.ToolCatalog;
import com.piagent.infrastructure.mcp.McpToolNamespace;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 支持插件动态注册的运行时工具目录。
 *
 * <p>内置工具与已激活插件工具合并成一个不可变 {@link ToolCatalog}，整体替换而非
 * 原地修改。run 开始时通过 {@link #snapshot()} 固定目录，插件启停不会改变正在
 * 运行的 run 看到的工具清单。</p>
 */
public final class RuntimeToolRegistry implements ToolRegistry {

    private final List<ToolDescriptor> builtIns;
    private final Map<PluginId, List<ToolDescriptor>> pluginTools = new LinkedHashMap<>();
    private final Map<String, List<ToolDescriptor>> mcpTools = new LinkedHashMap<>();
    private volatile ToolCatalog catalog;

    /**
     * 创建注册表。
     *
     * @param builtIns 内置工具定义
     */
    public RuntimeToolRegistry(Collection<ToolDescriptor> builtIns) {
        this.builtIns = List.copyOf(builtIns);
        this.catalog = new ToolCatalog(this.builtIns);
    }

    /**
     * 替换某个插件的工具集合。
     *
     * @param pluginId 插件标识
     * @param tools 工具定义
     */
    public synchronized void replace(PluginId pluginId, List<ToolDescriptor> tools) {
        Map<PluginId, List<ToolDescriptor>> candidate = new LinkedHashMap<>(pluginTools);
        candidate.put(pluginId, List.copyOf(tools));
        this.catalog = buildCatalog(candidate, mcpTools);
        this.pluginTools.put(pluginId, List.copyOf(tools));
    }

    /**
     * 移除某个插件的工具。
     *
     * @param pluginId 插件标识
     */
    public synchronized void remove(PluginId pluginId) {
        if (!pluginTools.containsKey(pluginId)) {
            return;
        }
        Map<PluginId, List<ToolDescriptor>> candidate = new LinkedHashMap<>(pluginTools);
        candidate.remove(pluginId);
        this.catalog = buildCatalog(candidate, mcpTools);
        this.pluginTools.remove(pluginId);
    }

    /**
     * 替换某个 MCP 服务器的工具集合。
     *
     * <p>名称必须带 {@code <server>.} 前缀；与内置工具、插件工具或其他服务器
     * 冲突时整体拒绝，不破坏现有目录。</p>
     *
     * @param server 服务器名
     * @param tools 工具定义
     */
    public synchronized void replaceMcp(String server, List<ToolDescriptor> tools) {
        if (server == null || server.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "MCP 服务器名不能为空");
        }
        List<ToolDescriptor> frozen = List.copyOf(tools);
        Map<String, List<ToolDescriptor>> candidate = new LinkedHashMap<>(mcpTools);
        candidate.put(server, frozen);
        this.catalog = buildCatalog(pluginTools, candidate);
        this.mcpTools.put(server, frozen);
    }

    /**
     * 移除某个 MCP 服务器的工具。
     *
     * @param server 服务器名
     */
    public synchronized void removeMcp(String server) {
        if (!mcpTools.containsKey(server)) {
            return;
        }
        Map<String, List<ToolDescriptor>> candidate = new LinkedHashMap<>(mcpTools);
        candidate.remove(server);
        this.catalog = buildCatalog(pluginTools, candidate);
        this.mcpTools.remove(server);
    }

    /**
     * 某个插件当前注册的工具。
     *
     * @param pluginId 插件标识
     * @return 工具定义
     */
    public synchronized List<ToolDescriptor> toolsOf(PluginId pluginId) {
        return pluginTools.getOrDefault(pluginId, List.of());
    }

    @Override
    public List<ToolDescriptor> descriptors() {
        return catalog.descriptors();
    }

    @Override
    public Optional<ToolDescriptor> find(ToolName name) {
        return catalog.find(name);
    }

    @Override
    public ToolDescriptor require(ToolName name) {
        return catalog.require(name);
    }

    private ToolCatalog buildCatalog(Map<PluginId, List<ToolDescriptor>> plugins,
            Map<String, List<ToolDescriptor>> mcpServers) {
        List<ToolDescriptor> merged = new ArrayList<>(builtIns);
        Set<ToolName> names = new HashSet<>();
        builtIns.forEach(tool -> names.add(tool.name()));
        for (Map.Entry<PluginId, List<ToolDescriptor>> entry : plugins.entrySet()) {
            for (ToolDescriptor tool : entry.getValue()) {
                PluginCompatibilityChecker.requireToolPrefix(entry.getKey(), tool.name());
                PluginCompatibilityChecker.requireNoConflict(tool.name(), names);
                names.add(tool.name());
                merged.add(tool);
            }
        }
        for (Map.Entry<String, List<ToolDescriptor>> entry : mcpServers.entrySet()) {
            for (ToolDescriptor tool : entry.getValue()) {
                McpToolNamespace.requireOwned(entry.getKey(), tool.name());
                if (!names.add(tool.name())) {
                    throw new DomainException(DomainErrorCode.MCP_TOOL_CONFLICT,
                            "MCP 工具名已被占用: " + tool.name());
                }
                merged.add(tool);
            }
        }
        return new ToolCatalog(merged);
    }
}
