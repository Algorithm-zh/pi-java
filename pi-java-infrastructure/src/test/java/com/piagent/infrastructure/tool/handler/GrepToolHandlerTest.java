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

class GrepToolHandlerTest {

    @TempDir
    Path workspace;

    private GrepToolHandler handler;
    private SandboxPolicy policy;

    @BeforeEach
    void setUp() throws IOException {
        handler = new GrepToolHandler(new RealPathResolver(new PathSandbox()));
        policy = SandboxPolicy.readOnly(workspace);
        Path src = Files.createDirectories(workspace.resolve("src"));
        Files.writeString(src.resolve("Main.java"), "hello world\nsecond line\n");
        Files.writeString(src.resolve("Other.txt"), "hello there\n");
        Files.writeString(workspace.resolve("README.md"), "Hello project\n");
        Path hidden = Files.createDirectories(workspace.resolve(".hidden"));
        Files.writeString(hidden.resolve("secret.txt"), "hello hidden\n");
        Path target = Files.createDirectories(workspace.resolve("target"));
        Files.writeString(target.resolve("Gen.java"), "hello generated\n");
        Files.write(workspace.resolve("blob.bin"), new byte[] {'h', 0, 'i'});
    }

    @Test
    void searchesTextFilesAndSkipsNoise() {
        ToolResult result = execute(Map.of("pattern", "hello"));

        assertThat(result.content()).contains("src/Main.java:1: hello world")
                .contains("src/Other.txt:1: hello there")
                .doesNotContain("README.md")
                .doesNotContain(".hidden")
                .doesNotContain("target/");
        assertThat(result.details()).containsEntry("matches", 2)
                .containsEntry("skippedBinary", 1)
                .containsEntry("truncated", false);
    }

    @Test
    void honoursIgnoreCase() {
        ToolResult result = execute(Map.of("pattern", "hello", "ignoreCase", true));

        assertThat(result.content()).contains("README.md:1: Hello project");
        assertThat(result.details()).containsEntry("matches", 3);
    }

    @Test
    void filtersByFileNameGlob() {
        ToolResult result = execute(Map.of("pattern", "hello", "glob", "*.java"));

        assertThat(result.content()).contains("src/Main.java").doesNotContain("Other.txt");
        assertThat(result.details()).containsEntry("matches", 1);
    }

    @Test
    void filtersByRelativePathGlob() {
        ToolResult result = execute(Map.of("pattern", "hello", "glob", "src/*.txt"));

        assertThat(result.content()).contains("src/Other.txt").doesNotContain("Main.java");
    }

    @Test
    void searchesSingleFile() {
        ToolResult result = execute(Map.of("pattern", "second", "path", "src/Main.java"));

        assertThat(result.content()).contains("src/Main.java:2: second line");
    }

    @Test
    void rejectsInvalidRegex() {
        assertThatThrownBy(() -> execute(Map.of("pattern", "[")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void truncatesAtMaxResults() {
        ToolResult result = execute(Map.of("pattern", "hello", "maxResults", 1));

        assertThat(result.details()).containsEntry("matches", 1)
                .containsEntry("truncated", true);
    }

    @Test
    void reportsNoMatches() {
        ToolResult result = execute(Map.of("pattern", "nothing-matches-this"));

        assertThat(result.content()).isEqualTo("（没有匹配）");
    }

    @Test
    void rejectsPathOutsideWorkspace() {
        assertThatThrownBy(() -> execute(Map.of("pattern", "x", "path", "..")))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    private ToolResult execute(Map<String, Object> arguments) {
        ToolInvocation invocation = new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("grep"), arguments, policy);
        return handler.execute(invocation);
    }
}
