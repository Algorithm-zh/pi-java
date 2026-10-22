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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditToolHandlerTest {

    @TempDir
    Path workspace;

    private EditToolHandler handler;

    private SandboxPolicy policy;

    @BeforeEach
    void setUp() {
        handler = new EditToolHandler(new RealPathResolver(new PathSandbox()));
        policy = SandboxPolicy.workspaceWritable(workspace);
    }

    @Test
    void replacesUniqueOccurrence() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "one two three");

        ToolResult result = execute("a.txt", "one", "ONE", Map.of());

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("ONE two three");
        assertThat(result.details()).containsEntry("occurrences", 1)
                .containsEntry("replacements", 1);
    }

    @Test
    void replacesAllWhenRequested() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "one two one");

        ToolResult result = execute("a.txt", "one", "ONE", Map.of("replaceAll", true));

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("ONE two ONE");
        assertThat(result.details()).containsEntry("occurrences", 2)
                .containsEntry("replacements", 2);
    }

    @Test
    void acceptsEmptyReplacement() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "keep-remove-keep");

        ToolResult result = execute("a.txt", "-remove", "", Map.of());

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("keep-keep");
    }

    @Test
    void rejectsAmbiguousMatchWithoutReplaceAll() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "same same");

        assertThatThrownBy(() -> execute("a.txt", "same", "other", Map.of()))
                .isInstanceOfSatisfying(DomainException.class, error -> {
                    assertThat(error.code()).isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT);
                    assertThat(error.getMessage()).contains("2 处");
                });
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("same same");
    }

    @Test
    void rejectsMissingText() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "content");

        assertThatThrownBy(() -> execute("a.txt", "absent", "new", Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsMissingFile() {
        assertThatThrownBy(() -> execute("missing.txt", "a", "b", Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_NOT_FOUND));
    }

    @Test
    void rejectsBinaryFile() throws IOException {
        Files.write(workspace.resolve("bin.dat"), new byte[] {1, 0, 2, 3});

        assertThatThrownBy(() -> execute("bin.dat", "a", "b", Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsPathOutsideWorkspace() throws IOException {
        Path outside = workspace.getParent().resolve("outside-edit.txt");
        Files.writeString(outside, "secret");

        assertThatThrownBy(() -> execute("../outside-edit.txt", "secret", "changed", Map.of()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
        Files.deleteIfExists(outside);
    }

    @Test
    void rejectsUnknownArguments() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "content");

        assertThatThrownBy(() -> execute("a.txt", "content", "new",
                Map.of("regex", true)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    private ToolResult execute(String path, String oldText, String newText,
            Map<String, Object> extra) {
        Map<String, Object> arguments = new HashMap<>(extra);
        arguments.put("path", path);
        arguments.put("oldText", oldText);
        arguments.put("newText", newText);
        return handler.execute(new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("edit"), arguments, policy));
    }
}
