package com.piagent.example;

import com.piagent.plugin.api.PiPlugin;
import com.piagent.plugin.api.PluginContext;
import com.piagent.plugin.api.PluginDescriptor;
import com.piagent.plugin.api.PluginId;

/**
 * 示例插件入口：注册一个问候工具。
 *
 * <p>插件只依赖 {@code pi-java-plugin-api}，不引用宿主领域类型或 Spring。
 * 入口类必须有无参构造器，且描述信息与 jar 内清单保持一致。</p>
 */
public final class HelloPlugin implements PiPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(PluginId.parse("com.piagent.example.hello"), "Hello Plugin",
                "1.0.0", "1.0", "com.piagent.example.HelloPlugin",
                "示例插件：演示零依赖契约与工具注册");
    }

    @Override
    public void start(PluginContext context) {
        context.registerTool(new HelloTool());
        context.log().info("Hello 插件已启动");
    }
}
