package com.piagent.infrastructure.plugin;

import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.port.ToolInvoker;
import com.piagent.infrastructure.tool.BuiltInToolInvoker;
import java.util.Objects;

/**
 * 运行时工具调用分派器。
 *
 * <p>插件工具优先匹配已加载句柄；其余交给内置工具调用器。两者使用同一条
 * 策略、预算与结果管线，插件没有绕过治理的入口。</p>
 */
public final class RuntimeToolInvoker implements ToolInvoker {

    private final BuiltInToolInvoker builtIn;
    private final PluginHost plugins;

    /**
     * 创建分派器。
     *
     * @param builtIn 内置工具调用器
     * @param plugins 插件宿主
     */
    public RuntimeToolInvoker(BuiltInToolInvoker builtIn, PluginHost plugins) {
        this.builtIn = Objects.requireNonNull(builtIn, "builtIn 不能为空");
        this.plugins = Objects.requireNonNull(plugins, "plugins 不能为空");
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        if (plugins.hasTool(invocation.tool())) {
            return plugins.invoke(invocation);
        }
        return builtIn.invoke(invocation);
    }
}
