package com.piagent.example.timer;

import com.piagent.plugin.api.PiPlugin;
import com.piagent.plugin.api.PluginContext;
import com.piagent.plugin.api.PluginDescriptor;
import com.piagent.plugin.api.PluginId;

/**
 * 定时器插件入口：注册一个倒计时工具。
 *
 * <p>只依赖 {@code pi-java-plugin-api}，不引用宿主内部类型或 Spring。
 * 描述信息必须与 jar 内清单保持一致。</p>
 */
public final class TimerPlugin implements PiPlugin {

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(PluginId.parse("com.piagent.example.timer"), "定时器",
                "1.0.0", "1.0", "com.piagent.example.timer.TimerPlugin",
                "可取消的倒计时工具，支持 0.1 到 300 秒");
    }

    @Override
    public void start(PluginContext context) {
        context.registerTool(new CountdownTool());
        context.log().info("Timer 插件已启动");
    }
}
