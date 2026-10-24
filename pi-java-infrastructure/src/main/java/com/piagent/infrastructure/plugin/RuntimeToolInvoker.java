package com.piagent.infrastructure.plugin;

import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.infrastructure.mcp.McpHost;
import com.piagent.infrastructure.tool.BuiltInToolInvoker;
import java.util.Objects;

/**
 * 运行时工具调用分派器。
 *
 * <p>按来源分派：MCP 工具 → 插件工具 → 内置工具。三者使用同一条策略、预算、
 * 审批与结果管线，任何来源都没有绕过治理的入口。</p>
 */
public final class RuntimeToolInvoker implements ToolInvoker {

    private final BuiltInToolInvoker builtIn;
    private final PluginHost plugins;
    private final McpHost mcp;

    /**
     * 创建分派器。
     *
     * @param builtIn 内置工具调用器
     * @param plugins 插件宿主
     * @param mcp MCP 宿主
     */
    public RuntimeToolInvoker(BuiltInToolInvoker builtIn, PluginHost plugins, McpHost mcp) {
        this.builtIn = Objects.requireNonNull(builtIn, "builtIn 不能为空");
        this.plugins = Objects.requireNonNull(plugins, "plugins 不能为空");
        this.mcp = Objects.requireNonNull(mcp, "mcp 不能为空");
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        if (mcp.hasTool(invocation.tool())) {
            return mcp.invoke(invocation);
        }
        if (plugins.hasTool(invocation.tool())) {
            return plugins.invoke(invocation);
        }
        return builtIn.invoke(invocation);
    }
}
