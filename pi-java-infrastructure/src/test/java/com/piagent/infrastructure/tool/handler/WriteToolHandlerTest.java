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

class WriteToolHandlerTest {

    @TempDir
    Path workspace;

    private WriteToolHandler handler;

    private SandboxPolicy policy;

    @BeforeEach
    void setUp() {
        handler = new WriteToolHandler(new RealPathResolver(new PathSandbox()));
        policy = SandboxPolicy.workspaceWritable(workspace);
    }

    @Test
    void createsFileAndParentDirectories() throws IOException {
        ToolResult result = execute("nested/dir/a.txt", "hello\nworld\n");

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("nested/dir/a.txt")))
                .isEqualTo("hello\nworld\n");
        assertThat(result.content()).contains("已创建文件 nested/dir/a.txt");
        assertThat(result.details()).containsEntry("path", "nested/dir/a.txt")
                .containsEntry("lines", 2)
                .containsEntry("created", true);
    }

    @Test
    void overwritesExistingFileAndReportsUpdate() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "old content");

        ToolResult result = execute("a.txt", "new content");

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("new content");
        assertThat(result.details()).containsEntry("created", false);
    }

    @Test
    void acceptsEmptyContent() throws IOException {
        ToolResult result = execute("empty.txt", "");

        assertThat(result.error()).isFalse();
        assertThat(Files.readString(workspace.resolve("empty.txt"))).isEmpty();
        assertThat(result.details()).containsEntry("lines", 0).containsEntry("bytes", 0L);
    }

    @Test
    void rejectsDirectoryTarget() throws IOException {
        Files.createDirectory(workspace.resolve("dir"));

        assertThatThrownBy(() -> execute("dir", "data"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    @Test
    void rejectsPathOutsideWritableRoots() {
        assertThatThrownBy(() -> execute("../outside-write.txt", "data"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void rejectsWriteWhenPolicyIsReadOnly() {
        policy = SandboxPolicy.readOnly(workspace);

        assertThatThrownBy(() -> execute("a.txt", "data"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void rejectsUnknownArguments() {
        assertThatThrownBy(() -> execute("a.txt", "data", Map.of("append", true)))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.TOOL_INVALID_ARGUMENT));
    }

    private ToolResult execute(String path, String content) {
        return execute(path, content, Map.of());
    }

    private ToolResult execute(String path, String content, Map<String, Object> extra) {
        Map<String, Object> arguments = new HashMap<>(extra);
        arguments.put("path", path);
        arguments.put("content", content);
        return handler.execute(new ToolInvocation(new ToolCallId("call-1"),
                new ToolName("write"), arguments, policy));
    }
}
