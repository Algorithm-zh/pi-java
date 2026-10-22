package com.piagent.infrastructure.plugin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.domain.tool.port.ToolRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 运行时工具目录测试：合并、快照与命名冲突。
 */
class RuntimeToolRegistryTest {

    private static final PluginId PLUGIN = new PluginId("example.hello");

    @Test
    void mergesBuiltInAndPluginTools() {
        RuntimeToolRegistry registry = new RuntimeToolRegistry(List.of(descriptor("read")));

        registry.replace(PLUGIN, List.of(descriptor("example.hello.echo")));

        assertThat(registry.descriptors()).extracting(item -> item.name().value())
                .containsExactly("read", "example.hello.echo");
        assertThat(registry.find(new ToolName("example.hello.echo"))).isPresent();
        assertThat(registry.toolsOf(PLUGIN)).hasSize(1);
    }

    @Test
    void snapshotSurvivesPluginRemoval() {
        RuntimeToolRegistry registry = new RuntimeToolRegistry(List.of(descriptor("read")));
        registry.replace(PLUGIN, List.of(descriptor("example.hello.echo")));

        ToolRegistry snapshot = registry.snapshot();
        registry.remove(PLUGIN);

        assertThat(snapshot.find(new ToolName("example.hello.echo"))).isPresent();
        assertThat(registry.find(new ToolName("example.hello.echo"))).isEmpty();
        assertThat(registry.toolsOf(PLUGIN)).isEmpty();
    }

    @Test
    void rejectsToolWithoutPluginPrefix() {
        RuntimeToolRegistry registry = new RuntimeToolRegistry(List.of(descriptor("read")));

        assertThatThrownBy(() -> registry.replace(PLUGIN, List.of(descriptor("other.echo"))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_TOOL_CONFLICT));
        assertThat(registry.descriptors()).hasSize(1);
    }

    @Test
    void rejectsDuplicateToolNames() {
        RuntimeToolRegistry registry = new RuntimeToolRegistry(List.of(descriptor("read")));

        assertThatThrownBy(() -> registry.replace(PLUGIN, List.of(
                descriptor("example.hello.echo"), descriptor("example.hello.echo"))))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PLUGIN_TOOL_CONFLICT));
    }

    private ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(new ToolName(name), name + " description",
                "{\"type\":\"object\"}", ToolRisk.LOW, ExecutionMode.PARALLEL,
                Duration.ofSeconds(5));
    }
}
