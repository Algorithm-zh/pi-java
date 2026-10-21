package com.piagent.domain.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 纯路径沙箱测试：不创建符号链接，只验证语法边界。
 */
class PathSandboxTest {

    private final PathSandbox sandbox = new PathSandbox();

    @TempDir
    Path tempDir;

    @Test
    void resolvesRelativePathInsideWorkspace() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path file = Files.writeString(workspace.resolve("a.txt"), "hi");

        Path resolved = sandbox.resolveReadable(SandboxPolicy.readOnly(workspace), "a.txt");

        assertThat(resolved).isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void resolvesDotDotWithinWorkspace() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.createDirectories(workspace.resolve("sub"));

        Path resolved = sandbox.resolveReadable(
                SandboxPolicy.readOnly(workspace), "sub/../b.txt");

        assertThat(resolved).isEqualTo(workspace.resolve("b.txt").toAbsolutePath().normalize());
    }

    @Test
    void rejectsTraversalOutsideWorkspace() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));

        assertThatThrownBy(() -> sandbox.resolveReadable(
                SandboxPolicy.readOnly(workspace), "../outside.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "secret");

        assertThatThrownBy(() -> sandbox.resolveReadable(
                SandboxPolicy.readOnly(workspace), outside.toString()))
                .isInstanceOf(DomainException.class);
    }

    @Test
    void allowsReadOutsideOnlyWhenPolicyPermits() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = Files.writeString(tempDir.resolve("outside.txt"), "shared");
        SandboxPolicy policy = new SandboxPolicy(workspace, java.util.List.of(), true);

        assertThat(sandbox.resolveReadable(policy, outside.toString())).isEqualTo(outside);
    }

    @Test
    void blankPathMeansWorkspaceRoot() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));

        assertThat(sandbox.resolveReadable(SandboxPolicy.readOnly(workspace), " "))
                .isEqualTo(workspace.toAbsolutePath().normalize());
    }

    @Test
    void writeRequiresWritableRoot() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Path file = workspace.resolve("a.txt");

        assertThatThrownBy(() -> sandbox.resolveWritable(
                SandboxPolicy.readOnly(workspace), "a.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
        assertThat(sandbox.resolveWritable(SandboxPolicy.workspaceWritable(workspace), "a.txt"))
                .isEqualTo(file.toAbsolutePath().normalize());
    }

    @Test
    void rejectsNullCharacter() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));

        assertThatThrownBy(() -> sandbox.resolveReadable(
                SandboxPolicy.readOnly(workspace), "a\0b"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.PATH_INVALID));
    }
}
