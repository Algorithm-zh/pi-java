package com.piagent.infrastructure.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.piagent.domain.governance.model.SandboxPolicy;
import com.piagent.domain.governance.service.PathSandbox;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RealPathResolverTest {

    @TempDir
    Path workspace;

    private RealPathResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new RealPathResolver(new PathSandbox());
    }

    @Test
    void resolvesExistingFileInsideWorkspace() throws IOException {
        Path file = Files.writeString(workspace.resolve("a.txt"), "hello");
        SandboxPolicy policy = SandboxPolicy.readOnly(workspace);

        assertThat(resolver.resolveReadable(policy, "a.txt")).isEqualTo(file.toRealPath());
    }

    @Test
    void blankPathResolvesToWorkspaceRoot() throws IOException {
        SandboxPolicy policy = SandboxPolicy.readOnly(workspace);

        assertThat(resolver.resolveReadable(policy, " "))
                .isEqualTo(workspace.toRealPath());
    }

    @Test
    void rejectsTraversalOutsideWorkspace() throws IOException {
        Path outside = Files.writeString(workspace.getParent().resolve("outside-m3.txt"), "x");
        SandboxPolicy policy = SandboxPolicy.readOnly(workspace);

        assertThatThrownBy(() -> resolver.resolveReadable(policy, "../outside-m3.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
        Files.deleteIfExists(outside);
    }

    @Test
    void rejectsAbsolutePathOutsideWorkspace() {
        Path outside = workspace.getParent().toAbsolutePath();
        SandboxPolicy policy = SandboxPolicy.readOnly(workspace);

        assertThatThrownBy(() -> resolver.resolveReadable(policy, outside.toString()))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void rejectsMissingPath() {
        SandboxPolicy policy = SandboxPolicy.readOnly(workspace);

        assertThatThrownBy(() -> resolver.resolveReadable(policy, "missing.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_NOT_FOUND));
    }

    @Test
    void rejectsSymlinkPointingOutsideWorkspace() throws IOException {
        Path outside = Files.createTempFile("pi-java-outside", ".txt");
        Path link = workspace.resolve("escape.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Files.deleteIfExists(outside);
            assumeTrue(false, "当前环境无法创建符号链接");
            return;
        }
        SandboxPolicy policy = SandboxPolicy.readOnly(workspace);

        assertThatThrownBy(() -> resolver.resolveReadable(policy, "escape.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
        Files.deleteIfExists(link);
        Files.deleteIfExists(outside);
    }

    @Test
    void writableTargetMayNotExistYet() throws IOException {
        SandboxPolicy policy = SandboxPolicy.workspaceWritable(workspace);

        Path target = resolver.resolveWritable(policy, "new/dir/file.txt");

        assertThat(target).isEqualTo(workspace.toRealPath().resolve("new/dir/file.txt"));
    }

    @Test
    void writablePathOutsideWorkspaceIsRejected() {
        SandboxPolicy policy = SandboxPolicy.workspaceWritable(workspace);

        assertThatThrownBy(() -> resolver.resolveWritable(policy, "../escape.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
    }

    @Test
    void writableSymlinkedDirectoryOutsideIsRejected() throws IOException {
        Path outsideDir = Files.createTempDirectory("pi-java-outside-dir");
        Path link = workspace.resolve("linked-dir");
        try {
            Files.createSymbolicLink(link, outsideDir);
        } catch (UnsupportedOperationException | IOException | SecurityException error) {
            Files.deleteIfExists(outsideDir);
            assumeTrue(false, "当前环境无法创建符号链接");
            return;
        }
        SandboxPolicy policy = SandboxPolicy.workspaceWritable(workspace);

        assertThatThrownBy(() -> resolver.resolveWritable(policy, "linked-dir/new.txt"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code())
                                .isEqualTo(DomainErrorCode.PATH_OUTSIDE_WORKSPACE));
        Files.deleteIfExists(link);
        Files.deleteIfExists(outsideDir);
    }
}
