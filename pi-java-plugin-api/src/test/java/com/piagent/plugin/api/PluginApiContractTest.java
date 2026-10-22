package com.piagent.plugin.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PluginApiContractTest {

    @Test
    void descriptorAndToolSpecValidateInput() {
        PluginDescriptor descriptor = new PluginDescriptor(PluginId.parse("com.example.hello"),
                "Hello", "1.0.0", "1", "com.example.hello.HelloPlugin", "示例");

        assertThat(descriptor.id().toString()).isEqualTo("com.example.hello");
        assertThatThrownBy(() -> new ToolSpec("Bad.Name", "d", "{}", PluginRisk.LOW,
                Duration.ofSeconds(1), true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolSpec("echo", "d", "{}", PluginRisk.LOW,
                Duration.ZERO, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PluginId.parse("Com.Example"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toolCallAndResultFreezeArguments() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("name", "pi");
        PluginToolCall call = new PluginToolCall("call-1", "com.example.hello.echo", arguments);
        arguments.put("name", "changed");

        assertThat(call.arguments()).containsEntry("name", "pi");
        assertThatThrownBy(() -> call.arguments().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);

        PluginToolResult result = new PluginToolResult("ok", false, null);
        assertThat(result.metadata()).isEmpty();
        assertThat(PluginToolResult.failure("boom").error()).isTrue();
    }

    @Test
    void registrationIsAFunctionalInterface() {
        boolean[] closed = {false};
        Registration registration = () -> closed[0] = true;

        registration.close();

        assertThat(closed[0]).isTrue();
    }
}
