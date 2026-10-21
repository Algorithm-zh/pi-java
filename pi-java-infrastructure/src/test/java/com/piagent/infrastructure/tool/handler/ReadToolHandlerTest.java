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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReadToolHandlerTest {

    @TempDir
    Path workspace;

    private ReadToolHandler handler;
    private SandboxPolicy policy;

    @BeforeEach
    void setUp() {
        handler = new ReadToolHandler(new RealPathResolver(new PathSandbox()));
        policy = SandboxPolicy.readOnly(workspace);
    }

    @Test
    void readsFileWithLineNumbers() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "first\nsecond\nthird\n");

        ToolResult result = execute("a.txt", Map.of());

        assertThat(result.error()).isFalse();
        assertThat(result.content()).isEqualTo("    1| first\n    2| second\n    3| third\n");
        assertThat(result.details()).containsEntry("lines", 3)
                .containsEntry("truncated", false)
                .containsEntry("path", "a.txt");
    }

    @Test
    void honoursOffsetAndLimit() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "one\ntwo\nthree\nfour\n");

        ToolResult result = execute("a.txt", Map.of("offset", 2, "limit", 2));

        assertThat(result.content()).isEqualTo("    2| two\n    3| three\n");
        assertThat(result.details()).containsEntry("lines", 2);
    }

    @Test
    void reportsOffsetBeyondEndOfFile() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "one\ntwo\n");

        ToolResult result = execute("a.txt", Map.of("offset", 10));

        assertThat(result.content()).contains("文件共 2 行");
        assertThat(result.details()).containsEntry("lines", 0);
    }

    @Test
    void truncatesVeryLongLines() throws IOException {
        String longLine = "x".repeat(2_500);
        Files.writeString(workspace.resolve("long.txt"), longLine + "\n");

        ToolResult result = execute("long.txt", Map.of());

        assertThat(result.content()).contains("…").doesNotContain("x".repeat(2_001));
        assertThat(result.details()).containsEntry("truncated", false);
    }

    @Test
    void rejectsBinaryFile() throws IOException {
        Files.write(workspace.resolve("bin.dat"), new byte[] {1, 0, 2, 3});

        assertThatThrownBy(() -> execute("bin.dat", Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsDirectory() throws IOException {
        Files.createDirectory(workspace.resolve("dir"));

        assertThatThrownBy(() -> execute("dir", Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsPathOutsideWorkspace() throws IOException {
        Files.writeString(workspace.getParent().resolve("outside-read.txt"), "secret");

        assertThatThrownBy(() -> execute("../outside-read.txt", Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
        Files.deleteIfExists(workspace.getParent().resolve("outside-read.txt"));
    }

    @Test
    void rejectsUnknownArguments() {
        assertThatThrownBy(() -> execute("a.txt", Map.of("unexpected", 1)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    private ToolResult execute(String path, Map<String, Object> extra) {
        Map<String, Object> arguments = new java.util.HashMap<>(extra);
        arguments.put("path", path);
        return handler.execute(invocation("read", arguments));
    }

    private ToolInvocation invocation(String tool, Map<String, Object> arguments) {
        return new ToolInvocation(new ToolCallId("call-1"), new ToolName(tool), arguments, policy);
    }
}
