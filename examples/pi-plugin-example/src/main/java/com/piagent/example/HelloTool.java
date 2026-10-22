package com.piagent.example;

import com.piagent.plugin.api.PluginRisk;
import com.piagent.plugin.api.PluginTool;
import com.piagent.plugin.api.PluginToolCall;
import com.piagent.plugin.api.PluginToolContext;
import com.piagent.plugin.api.PluginToolResult;
import com.piagent.plugin.api.ToolSpec;
import java.time.Duration;

/**
 * 问候工具：输入名字，返回一句问候。
 *
 * <p>工具实现不读取进程工作目录，也不访问网络；失败通过
 * {@link PluginToolResult#failure(String)} 返回，由模型自行纠正参数。</p>
 */
public final class HelloTool implements PluginTool {

    private static final String SCHEMA = """
            {"type":"object",
             "properties":{"name":{"type":"string","description":"要问候的名字"}},
             "required":["name"],"additionalProperties":false}
            """;

    @Override
    public ToolSpec spec() {
        return new ToolSpec("greet", "向指定名字发送问候", SCHEMA, PluginRisk.LOW,
                Duration.ofSeconds(5), true);
    }

    @Override
    public PluginToolResult execute(PluginToolCall call, PluginToolContext context) {
        Object raw = call.arguments().get("name");
        String name = raw == null ? "" : raw.toString().trim();
        if (name.isEmpty()) {
            return PluginToolResult.failure("name 不能为空");
        }
        return PluginToolResult.success("你好，" + name + "！");
    }
}
