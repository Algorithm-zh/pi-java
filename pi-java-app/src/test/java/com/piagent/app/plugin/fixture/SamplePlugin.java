package com.piagent.app.plugin.fixture;

import com.piagent.plugin.api.PiPlugin;
import com.piagent.plugin.api.PluginContext;
import com.piagent.plugin.api.PluginDescriptor;
import com.piagent.plugin.api.PluginRisk;
import com.piagent.plugin.api.PluginTool;
import com.piagent.plugin.api.PluginToolCall;
import com.piagent.plugin.api.PluginToolContext;
import com.piagent.plugin.api.PluginToolResult;
import com.piagent.plugin.api.ToolSpec;
import java.time.Duration;
import java.util.Map;

/**
 * 集成测试用示例插件。
 *
 * <p>类文件会被打成 jar 后由宿主插件类加载器加载，因此这里只能依赖
 * plugin-api 与 JDK。工具返回 {@code echo:<text>}，用于验证真实调用链。</p>
 */
public final class SamplePlugin implements PiPlugin {

    /** 插件标识，测试用常量。 */
    public static final String ID = "app.test.sample";

    /** 工具完整名称。 */
    public static final String TOOL = ID + ".echo";

    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(new com.piagent.plugin.api.PluginId(ID), "Sample",
                "1.0.0", "1.0", SamplePlugin.class.getName(), "集成测试插件");
    }

    @Override
    public void start(PluginContext context) {
        context.registerTool(new EchoTool());
    }

    /**
     * 回声工具。
     */
    public static final class EchoTool implements PluginTool {

        @Override
        public ToolSpec spec() {
            return new ToolSpec("echo", "回显输入文本",
                    "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},"
                            + "\"required\":[\"text\"]}",
                    PluginRisk.LOW, Duration.ofSeconds(5), true);
        }

        @Override
        public PluginToolResult execute(PluginToolCall call, PluginToolContext context) {
            Object text = call.arguments().get("text");
            if (text == null || text.toString().isBlank()) {
                return PluginToolResult.failure("text 不能为空");
            }
            return PluginToolResult.success("echo:" + text);
        }
    }
}
