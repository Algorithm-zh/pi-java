package com.piagent.infrastructure.tool.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolInvocation;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolResult;
import com.piagent.infrastructure.tool.RealPathResolver;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BashToolHandlerTest {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).contains("win");

    @TempDir
    Path workspace;

    private RealPathResolver resolver;

    private BashToolHandler handler;

    private SandboxPolicy policy;

    @BeforeEach
    void setUp() {
        resolver = new RealPathResolver(new PathSandbox());
        handler = new BashToolHandler(resolver);
        policy = SandboxPolicy.workspaceWritable(workspace);
    }

    @Test
    void runsCommandAndCapturesOutput() {
        ToolResult result = execute(Map.of("command", "echo hello"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains("hello");
        assertThat(result.details()).containsEntry("exitCode", 0)
                .containsEntry("timedOut", false)
                .containsEntry("truncated", false);
    }

    @Test
    void reportsNonZeroExitCodeAsError() {
        ToolResult result = execute(Map.of("command", exitCommand(3)));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("退出码: 3");
        assertThat(result.details()).containsEntry("exitCode", 3);
    }

    @Test
    void runsInsideRequestedWorkingDirectory() {
        ToolResult result = execute(Map.of("command", WINDOWS ? "cd" : "pwd"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).contains(workspace.getFileName().toString());
    }

    @Test
    void truncatesOutputButStillConsumesProcess() {
        handler = new BashToolHandler(resolver, 20);

        ToolResult result = execute(Map.of("command", "echo 012345678901234567890123456789"));

        assertThat(result.error()).isFalse();
        assertThat(result.content()).startsWith("01234567890123456789").contains("输出已截断");
        assertThat(result.details()).containsEntry("truncated", true);
    }

    @Test
    void killsProcessTreeOnTimeout() {
        String command = WINDOWS ? "ping -n 6 127.0.0.1 > nul" : "sleep 5";

        ToolResult result = execute(Map.of("command", command, "timeoutSeconds", 1));

        assertThat(result.error()).isTrue();
        assertThat(result.content()).contains("超时");
        assertThat(result.details()).containsEntry("timedOut", true)
                .containsEntry("exitCode", -1);
        assertThat((Long) result.details().get("durationMs")).isLessThan(5_000L);
    }

    @Test
    void rejectsFileAsWorkingDirectory() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "content");

        assertThatThrownBy(() -> execute(Map.of("command", "echo hi", "cwd", "a.txt")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsWorkingDirectoryOutsideWorkspace() {
        assertThatThrownBy(() -> execute(Map.of("command", "echo hi", "cwd", "..")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void rejectsUnknownArguments() {
        assertThatThrownBy(() -> execute(Map.of("command", "echo hi", "env", Map.of())))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    private String exitCommand(int code) {
        return WINDOWS ? "exit /b " + code : "exit " + code;
    }

    private ToolResult execute(Map<String, Object> arguments) {
        return handler.execute(new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("bash"), new HashMap<>(arguments), policy));
    }
}
