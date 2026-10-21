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

class GlobToolHandlerTest {

    @TempDir
    Path workspace;

    private GlobToolHandler handler;
    private SandboxPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        handler = new GlobToolHandler(new RealPathResolver(new PathSandbox()));
        policy = SandboxPolicy.readOnly(workspace);
        Files.writeString(workspace.resolve("Root.java"), "root");
        Files.writeString(workspace.resolve("README.md"), "readme");
        Path src = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(src.resolve("Main.java"), "main");
        Files.writeString(src.resolve("Notes.md"), "notes");
        Path target = Files.createDirectories(workspace.resolve("target"));
        Files.writeString(target.resolve("Generated.java"), "generated");
        Path hidden = Files.createDirectories(workspace.resolve(".hidden"));
        Files.writeString(hidden.resolve("Hidden.java"), "hidden");
    }

    @Test
    void findsJavaFilesAtAnyDepth() {
        ToolResult result = execute(Map.of("pattern", "**/*.java"));

        assertThat(result.content()).isEqualTo("Root.java\nsrc/Main.java");
        assertThat(result.details()).containsEntry("matches", 2)
                .containsEntry("truncated", false);
    }

    @Test
    void matchesRootOnlyWithoutGlobstar() {
        ToolResult result = execute(Map.of("pattern", "*.md"));

        assertThat(result.content()).isEqualTo("README.md");
    }

    @Test
    void scopesSearchToSubdirectory() {
        ToolResult result = execute(Map.of("pattern", "*.java", "path", "src"));

        assertThat(result.content()).isEqualTo("src/Main.java");
    }

    @Test
    void truncatesAtMaxResults() {
        ToolResult result = execute(Map.of("pattern", "**/*.java", "maxResults", 1));

        assertThat(result.content().lines().count()).isEqualTo(1);
        assertThat(result.details()).containsEntry("truncated", true);
    }

    @Test
    void rejectsAbsolutePattern() {
        assertThatThrownBy(() -> execute(Map.of("pattern", "/tmp/*.java")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsInvalidPattern() {
        assertThatThrownBy(() -> execute(Map.of("pattern", "**/[")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void reportsNoMatches() {
        ToolResult result = execute(Map.of("pattern", "**/*.kt"));

        assertThat(result.content()).isEqualTo("（没有匹配）");
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        assertThatThrownBy(() -> execute(Map.of("pattern", "*.java", "path", "..")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    private ToolResult execute(Map<String, Object> arguments) {
        ToolInvocation invocation = new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("glob"), arguments, policy);
        return handler.execute(invocation);
    }
}
