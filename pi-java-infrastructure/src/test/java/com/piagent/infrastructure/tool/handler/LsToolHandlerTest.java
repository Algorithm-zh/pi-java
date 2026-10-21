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

class LsToolHandlerTest {

    @TempDir
    Path workspace;

    private LsToolHandler handler;
    private SandboxPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        handler = new LsToolHandler(new RealPathResolver(new PathSandbox()));
        policy = SandboxPolicy.readOnly(workspace);
        Files.writeString(workspace.resolve("b.txt"), "b");
        Files.writeString(workspace.resolve("a.txt"), "a");
        Path sub = Files.createDirectory(workspace.resolve("sub"));
        Files.writeString(sub.resolve("c.txt"), "c");
        Path hidden = Files.createDirectory(workspace.resolve(".hidden"));
        Files.writeString(hidden.resolve("secret.txt"), "secret");
        Path target = Files.createDirectory(workspace.resolve("target"));
        Files.writeString(target.resolve("generated.class"), "x");
    }

    @Test
    void listsDirectChildrenByDefault() {
        ToolResult result = execute(Map.of());

        assertThat(result.content()).isEqualTo("a.txt\nb.txt\nsub/");
        assertThat(result.details()).containsEntry("depth", 1)
                .containsEntry("entries", 3)
                .containsEntry("truncated", false);
    }

    @Test
    void listsRecursivelyUpToDepth() {
        ToolResult result = execute(Map.of("depth", 2));

        assertThat(result.content()).contains("sub/c.txt").doesNotContain(".hidden")
                .doesNotContain("target/");
    }

    @Test
    void listsSubdirectoryPath() {
        ToolResult result = execute(Map.of("path", "sub"));

        assertThat(result.content()).isEqualTo("sub/c.txt");
    }

    @Test
    void reportsEmptyDirectory() throws IOException {
        Files.createDirectory(workspace.resolve("empty"));

        ToolResult result = execute(Map.of("path", "empty"));

        assertThat(result.content()).isEqualTo("（空目录）");
        assertThat(result.details()).containsEntry("entries", 0);
    }

    @Test
    void rejectsFileAsDirectory() {
        assertThatThrownBy(() -> execute(Map.of("path", "a.txt")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        assertThatThrownBy(() -> execute(Map.of("path", "..")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    private ToolResult execute(Map<String, Object> arguments) {
        ToolInvocation invocation = new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("ls"), arguments, policy);
        return handler.execute(invocation);
    }
}
