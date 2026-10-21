package com.piagent.domain.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.domain.tool.service.ToolArgumentGuard;
import com.piagent.domain.tool.service.ToolCatalog;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 工具值对象、目录与参数校验的单元测试。
 */
class ToolModelTest {

    private static final SandboxPolicy SANDBOX = SandboxPolicy.readOnly(Path.of("workspace"));

    @Test
    void acceptsBuiltInAndPluginToolNames() {
        assertThat(new ToolName("read").value()).isEqualTo("read");
        assertThat(new ToolName("my-plugin.search").value()).isEqualTo("my-plugin.search");
        assertThat(new ToolName("qwen.ctx_1").value()).isEqualTo("qwen.ctx_1");
    }

    @Test
    void rejectsMalformedToolNames() {
        for (String value : List.of("Read", "read tool", "-read", "read.", "a".repeat(129))) {
            assertThatThrownBy(() -> new ToolName(value))
                    .as("工具名: %s", value)
                    .isInstanceOf(DomainException.class)
                    .extracting(error -> ((DomainException) error).code())
                    .isEqualTo(DomainErrorCode.INVALID_ARGUMENT);
        }
    }

    @Test
    void catalogRejectsDuplicatesAndRequiresKnownTools() {
        ToolDescriptor read = descriptor("read");
        ToolCatalog catalog = new ToolCatalog(List.of(read));

        assertThat(catalog.find(new ToolName("read"))).contains(read);
        assertThatThrownBy(() -> new ToolCatalog(List.of(read, descriptor("read"))))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("工具名重复");
        assertThatThrownBy(() -> catalog.require(new ToolName("write")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.TOOL_NOT_FOUND));
    }

    @Test
    void descriptorValidatesTimeoutAndSchema() {
        assertThatThrownBy(() -> new ToolDescriptor(new ToolName("read"), "读取", "{}",
                ToolRisk.LOW, ExecutionMode.PARALLEL, Duration.ZERO))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("超时");
        assertThatThrownBy(() -> new ToolDescriptor(new ToolName("read"), "读取", " ",
                ToolRisk.LOW, ExecutionMode.PARALLEL, Duration.ofSeconds(1)))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void resultNormalizesContentAndDetails() {
        ToolResult result = new ToolResult(new ToolCallId("call_1"), new ToolName("read"),
                null, false, null);

        assertThat(result.content()).isEmpty();
        assertThat(result.details()).isEmpty();
        assertThat(ToolResult.error(new ToolCallId("call_1"), new ToolName("read"), "boom").error())
                .isTrue();
    }

    @Test
    void invocationFreezesArguments() {
        Map<String, Object> arguments = new java.util.HashMap<>();
        arguments.put("path", "a.txt");
        ToolInvocation invocation = new ToolInvocation(new ToolCallId("call_1"),
                new ToolName("read"), arguments, SANDBOX);

        arguments.put("path", "b.txt");
        assertThat(invocation.arguments()).containsEntry("path", "a.txt");
    }

    @Test
    void argumentGuardRejectsUnknownAndWrongTypes() {
        Map<String, Object> args = Map.of("path", "a.txt", "offset", 2);
        ToolArgumentGuard.rejectUnknown(args, Set.of("path", "offset"));

        assertThatThrownBy(() -> ToolArgumentGuard.rejectUnknown(args, Set.of("path")))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("offset");
        assertThatThrownBy(() -> ToolArgumentGuard.requireString(
                Map.of("path", 1), "path", 100))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("字符串");
        assertThatThrownBy(() -> ToolArgumentGuard.optionalInt(args, "offset", 1, 1, 1))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("之间");
        assertThatThrownBy(() -> ToolArgumentGuard.optionalBoolean(
                Map.of("flag", "yes"), "flag", false))
                .isInstanceOf(DomainException.class)
                .hasMessageContaining("布尔值");
    }

    @Test
    void argumentGuardReadsOptionalValues() {
        Map<String, Object> args = Map.of("path", "a.txt", "limit", 10L,
                "ignoreCase", true);

        assertThat(ToolArgumentGuard.optionalString(args, "glob", "*", 100)).isEqualTo("*");
        assertThat(ToolArgumentGuard.optionalInt(args, "limit", 1, 1, 100)).isEqualTo(10);
        assertThat(ToolArgumentGuard.optionalBoolean(args, "ignoreCase", false)).isTrue();
        assertThat(ToolArgumentGuard.requireString(args, "path", 100)).isEqualTo("a.txt");
    }

    private static ToolDescriptor descriptor(String name) {
        return new ToolDescriptor(new ToolName(name), "读取文件", "{\"type\":\"object\"}",
                ToolRisk.LOW, ExecutionMode.PARALLEL, Duration.ofSeconds(5));
    }
}
