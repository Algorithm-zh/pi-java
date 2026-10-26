package com.piagent.example.timer;

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
 * 定时器插件契约测试：描述、注册、参数校验与取消。
 */
class TimerPluginTest {

    @Test
    void descriptorMatchesManifest() {
        var descriptor = new TimerPlugin().descriptor();

        assertThat(descriptor.id().value()).isEqualTo("com.piagent.example.timer");
        assertThat(descriptor.version()).isEqualTo("1.0.0");
        assertThat(descriptor.apiVersion()).isEqualTo("1.0");
        assertThat(descriptor.mainClass()).isEqualTo("com.piagent.example.timer.TimerPlugin");
    }

    @Test
    void startRegistersCountdownTool() {
        RecordingContext context = new RecordingContext();

        new TimerPlugin().start(context);

        assertThat(context.tool).isNotNull();
        assertThat(context.tool.spec().name()).isEqualTo("countdown");
        assertThat(context.tool.spec().risk().name()).isEqualTo("LOW");
    }

    @Test
    void countdownWaitsAndReportsElapsedTime() {
        CountdownTool tool = new CountdownTool();

        long startedAt = System.nanoTime();
        PluginToolResult result = tool.execute(
                call(Map.of("seconds", 0.2, "label", "泡茶")), context(() -> false));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("「泡茶」").contains("完成");
        assertThat(result.metadata()).containsKeys("requestedSeconds", "elapsedSeconds");
        assertThat(elapsedMillis).isGreaterThanOrEqualTo(180L);
    }

    @Test
    void countdownRejectsInvalidSeconds() {
        CountdownTool tool = new CountdownTool();

        assertThat(tool.execute(call(Map.of()), context(() -> false)).error()).isTrue();
        assertThat(tool.execute(call(Map.of("seconds", 0)), context(() -> false)).error()).isTrue();
        assertThat(tool.execute(call(Map.of("seconds", 301)), context(() -> false)).error()).isTrue();
        assertThat(tool.execute(call(Map.of("seconds", "abc")), context(() -> false)).error()).isTrue();
    }

    @Test
    void countdownStopsWhenCancelled() {
        CountdownTool tool = new CountdownTool();

        long startedAt = System.nanoTime();
        PluginToolResult result = tool.execute(
                call(Map.of("seconds", 5)), context(() -> true));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000L;

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("已取消");
        assertThat(elapsedMillis).isLessThan(1_000L);
    }

    private PluginToolCall call(Map<String, Object> arguments) {
        return new PluginToolCall("call-1", "com.piagent.example.timer.countdown", arguments);
    }

    private PluginToolContext context(CancellationToken cancellation) {
        return new PluginToolContext() {
            @Override
            public Path workspaceRoot() {
                return Path.of(".").toAbsolutePath();
            }

            @Override
            public CancellationToken cancellation() {
                return cancellation;
            }

            @Override
            public PluginLogger log() {
                return new SilentLogger();
            }

            @Override
            public void progress(String partialOutput) {
                // 定时器不产生增量输出。
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
