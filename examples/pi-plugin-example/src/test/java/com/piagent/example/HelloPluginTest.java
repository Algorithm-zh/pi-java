package com.piagent.example;

import static org.assertj.core.api.Assertions.assertThat;

import com.piagent.plugin.api.CancellationToken;
import com.piagent.plugin.api.PluginContext;
import com.piagent.plugin.api.PluginLogger;
import com.piagent.plugin.api.PluginTool;
import com.piagent.plugin.api.PluginToolCall;
import com.piagent.plugin.api.PluginToolContext;
import com.piagent.plugin.api.PluginToolResult;
import com.piagent.plugin.api.Registration;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 示例插件契约测试：描述、注册与工具行为。
 */
class HelloPluginTest {

    @Test
    void descriptorMatchesManifest() {
        var descriptor = new HelloPlugin().descriptor();

        assertThat(descriptor.id().value()).isEqualTo("com.piagent.example.hello");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.apiVersion()).isEqualTo("1.0");
        assertThat(descriptor.mainClass()).isEqualTo("com.piagent.example.HelloPlugin");
    }

    @Test
    void startRegistersGreetTool() {
        RecordingContext context = new RecordingContext();

        new HelloPlugin().start(context);

        assertThat(context.tool).isNotNull();
        assertThat(context.tool.spec().name()).isEqualTo("greet");
        assertThat(context.tool.spec().risk().name()).isEqualTo("LOW");
        assertThat(context.tool.spec().parallelSafe()).isTrue();
    }

    @Test
    void greetReturnsMessage() {
        HelloTool tool = new HelloTool();

        PluginToolResult result = tool.execute(call(Map.of("name", " Pi ")), context());

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("你好，Pi！");
    }

    @Test
    void greetRejectsMissingName() {
        HelloTool tool = new HelloTool();

        PluginToolResult result = tool.execute(call(Map.of()), context());

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("name");
    }

    private PluginToolCall call(Map<String, Object> arguments) {
        return new PluginToolCall("call-1", "com.piagent.example.hello.greet", arguments);
    }

    private PluginToolContext context() {
        return new PluginToolContext() {
            @Override
            public Path workspaceRoot() {
                return Path.of(".").toAbsolutePath();
            }

            @Override
            public CancellationToken cancellation() {
                return () -> false;
            }

            @Override
            public PluginLogger log() {
                return new SilentLogger();
            }

            @Override
            public void progress(String partialOutput) {
                // 示例工具不产生增量输出。
            }
        };
    }

    private static final class RecordingContext implements PluginContext {

        private PluginTool tool;

        @Override
        public Registration registerTool(PluginTool tool) {
            this.tool = tool;
            return () -> {
            };
        }

        @Override
        public PluginLogger log() {
            return new SilentLogger();
        }
    }

    private static final class SilentLogger implements PluginLogger {

        @Override
        public void debug(String message, Object... args) {
        }

        @Override
        public void info(String message, Object... args) {
        }

        @Override
        public void warn(String message, Object... args) {
        }

        @Override
        public void error(String message, Throwable error, Object... args) {
        }
    }
}
