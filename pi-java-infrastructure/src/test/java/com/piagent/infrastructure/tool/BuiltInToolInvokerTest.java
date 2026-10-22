package com.piagent.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ExecutionMode;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.domain.tool.model.ToolRisk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuiltInToolInvokerTest {

    @TempDir
    Path workspace;

    private BuiltInToolInvoker invoker;

    @AfterEach
    void tearDown() {
        if (invoker != null) {
            invoker.close();
        }
    }

    @Test
    void returnsHandlerResult() {
        invoker = new BuiltInToolInvoker(registry(descriptor("echo", Duration.ofSeconds(1))),
                Map.of(new ToolName("echo"), invocation -> ToolResult.success(
                        invocation.callId(), invocation.tool(), "pong", Map.of())));

        ToolResult result = invoker.invoke(invocation("echo"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("pong");
    }

    @Test
    void rejectsUnknownTool() {
        invoker = new BuiltInToolInvoker(registry(descriptor("echo", Duration.ofSeconds(1))),
                Map.of());

        assertThatThrownBy(() -> invoker.invoke(invocation("missing")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_NOT_FOUND));
    }

    @Test
    void mapsDomainExceptionToErrorResult() {
        invoker = new BuiltInToolInvoker(registry(descriptor("bad", Duration.ofSeconds(1))),
                Map.of(new ToolName("bad"), invocation -> {
                    throw new DomainException(DomainErrorCode.TOOL_INVALID_ARGUMENT, "参数坏了");
                }));

        ToolResult result = invoker.invoke(invocation("bad"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).isEqualTo("参数坏了");
        assertThat(result.details()).containsEntry("errorCode", "TOOL_INVALID_ARGUMENT");
    }

    @Test
    void wrapsUnexpectedException() {
        invoker = new BuiltInToolInvoker(registry(descriptor("boom", Duration.ofSeconds(1))),
                Map.of(new ToolName("boom"), invocation -> {
                    throw new IllegalStateException("磁盘炸了");
                }));

        ToolResult result = invoker.invoke(invocation("boom"));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("磁盘炸了");
        assertThat(result.details()).containsEntry("errorCode", "TOOL_EXECUTION_FAILED")
                .containsEntry("failureType", "IllegalStateException");
    }

    @Test
    void timesOutSlowHandler() {
        invoker = new BuiltInToolInvoker(registry(descriptor("slow", Duration.ofMillis(50))),
                Map.of(new ToolName("slow"), invocation -> {
                    sleep(2_000);
                    return ToolResult.success(invocation.callId(), invocation.tool(),
                            "late", Map.of());
                }));

        long started = System.nanoTime();
        ToolResult result = invoker.invoke(invocation("slow"));
        long elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(result.error()).isTrue();
        assertThat(result.details()).containsEntry("errorCode", "TOOL_TIMEOUT");
        assertThat(elapsedMs).isLessThan(1_500);
    }

    @Test
    void executesBuiltInReadToolEndToEnd() throws IOException {
        Files.writeString(workspace.resolve("hello.txt"), "hi\n");
        invoker = builtInInvoker();

        ToolResult result = invoker.invoke(new ToolInvocation(new ToolCallId("call-read"),
                new ToolName("read"), Map.of("path", "hello.txt"),
                SandboxPolicy.readOnly(workspace)));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("hi");
        assertThat(result.details()).containsEntry("path", "hello.txt");
    }

    private BuiltInToolInvoker builtInInvoker() {
        CatalogToolRegistry registry = new CatalogToolRegistry(BuiltInToolCatalog.descriptors());
        RealPathResolver resolver = new RealPathResolver(new PathSandbox());
        return new BuiltInToolInvoker(registry, BuiltInToolHandlers.create(resolver));
    }

    private CatalogToolRegistry registry(ToolDescriptor descriptor) {
        return new CatalogToolRegistry(List.of(descriptor));
    }

    private ToolDescriptor descriptor(String name, Duration timeout) {
        return new ToolDescriptor(new ToolName(name), "测试工具",
                "{\"type\":\"object\"}", ToolRisk.LOW, ExecutionMode.PARALLEL, timeout);
    }

    private ToolInvocation invocation(String tool) {
        return new ToolInvocation(new ToolCallId("call-1"), new ToolName(tool), Map.of(),
                SandboxPolicy.readOnly(workspace));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }
}
