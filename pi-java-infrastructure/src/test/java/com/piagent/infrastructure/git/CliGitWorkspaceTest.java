package com.piagent.infrastructure.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeKind;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.infrastructure.config.GitSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 真实 git 的临时仓库集成测试。
 *
 * <p>覆盖 M13 验收重点：未跟踪文件进入快照、还原后回到检查点、用户在本轮之后
 * 再改动时整体拒绝撤销，以及二进制文件的统计。</p>
 */
class CliGitWorkspaceTest {

    @TempDir
    Path repo;

    private final CliGitWorkspace workspace = new CliGitWorkspace(GitSettings.defaults());

    @Test
    void snapshotAndChangesCoverUntrackedAndBinaryFiles() throws IOException {
        GitSnapshot before = committedRepo();

        write("a.txt", "one\nthree\n");
        Files.delete(repo.resolve("b.txt"));
        write("new.txt", "fresh\n");
        Files.write(repo.resolve("logo.bin"), new byte[] {0, 1, 2, 3});
        GitSnapshot after = workspace.snapshot(repo).orElseThrow();

        GitChangeSet changes = workspace.changes(repo, before.treeSha(), after.treeSha());
        assertThat(after.headSha()).isEqualTo(before.headSha());
        assertThat(after.branch()).isNotBlank();
        assertThat(changes.files()).extracting(FileChange::path)
                .containsExactlyInAnyOrder("a.txt", "b.txt", "new.txt", "logo.bin");
        assertThat(change(changes, "a.txt").kind()).isEqualTo(GitChangeKind.MODIFIED);
        assertThat(change(changes, "b.txt").kind()).isEqualTo(GitChangeKind.DELETED);
        assertThat(change(changes, "new.txt").kind()).isEqualTo(GitChangeKind.ADDED);
        assertThat(change(changes, "logo.bin").binary()).isTrue();
        assertThat(changes.additions()).isPositive();
        assertThat(changes.diff()).contains("diff --git a/a.txt b/a.txt", "+three");
        assertThat(changes.truncated()).isFalse();
    }

    @Test
    void restoreRewindsModifiedDeletedAndUntrackedFiles() throws IOException {
        GitSnapshot before = committedRepo();
        write("a.txt", "one\nthree\n");
        Files.delete(repo.resolve("b.txt"));
        write("new.txt", "fresh\n");
        GitSnapshot after = workspace.snapshot(repo).orElseThrow();

        GitRestoreResult result = workspace.restore(repo, before.treeSha(), after.treeSha());

        assertThat(result.complete()).isTrue();
        assertThat(result.restored()).containsExactlyInAnyOrder(
                "a.txt", "b.txt", "new.txt");
        assertThat(Files.readString(repo.resolve("a.txt"))).isEqualTo("one\ntwo\n");
        assertThat(Files.readString(repo.resolve("b.txt"))).isEqualTo("keep\n");
        assertThat(Files.exists(repo.resolve("new.txt"))).isFalse();
        assertThat(trackedHead()).isEqualTo(before.headSha());
        assertThat(status()).isBlank();
    }

    @Test
    void restoreRefusesEverythingWhenUserChangedFilesAfterRun() throws IOException {
        GitSnapshot before = committedRepo();
        write("a.txt", "one\nagent\n");
        write("new.txt", "fresh\n");
        GitSnapshot after = workspace.snapshot(repo).orElseThrow();

        write("a.txt", "one\nuser\n");
        write("new.txt", "user edit\n");
        GitRestoreResult result = workspace.restore(repo, before.treeSha(), after.treeSha());

        assertThat(result.complete()).isFalse();
        assertThat(result.restored()).isEmpty();
        assertThat(result.conflicts()).extracting(conflict -> conflict.path())
                .containsExactlyInAnyOrder("a.txt", "new.txt");
        assertThat(Files.readString(repo.resolve("a.txt"))).isEqualTo("one\nuser\n");
        assertThat(Files.readString(repo.resolve("new.txt"))).isEqualTo("user edit\n");
    }

    @Test
    void truncatesLargeDiff() throws IOException {
        CliGitWorkspace small = new CliGitWorkspace(new GitSettings(true, 10, 1_000));
        GitSnapshot before = committedRepo();
        StringBuilder content = new StringBuilder();
        for (int index = 0; index < 400; index++) {
            content.append("line ").append(index).append('\n');
        }
        write("large.txt", content.toString());
        GitSnapshot after = small.snapshot(repo).orElseThrow();

        GitChangeSet changes = small.changes(repo, before.treeSha(), after.treeSha());

        assertThat(changes.truncated()).isTrue();
        assertThat(changes.diff()).endsWith("（diff 已截断）");
        assertThat(changes.diff().length()).isLessThan(1_100);
    }

    @Test
    void nonRepositoryIsReportedAsUnavailable() throws IOException {
        Path plain = Files.createDirectory(repo.resolve("plain"));

        assertThat(workspace.snapshot(plain)).isEmpty();
        assertThatThrownBy(() -> workspace.changes(plain, "a", "b"))
                .isInstanceOfSatisfying(DomainException.class,
                        error -> assertThat(error.code()).isEqualTo(DomainErrorCode.GIT_UNAVAILABLE));
    }

    private GitSnapshot committedRepo() throws IOException {
        git("init", "-q");
        write("a.txt", "one\ntwo\n");
        write("b.txt", "keep\n");
        git("add", "-A");
        git("-c", "user.name=Pi Test", "-c", "user.email=pi@example.com",
                "commit", "-q", "-m", "init");
        return workspace.snapshot(repo).orElseThrow();
    }

    private String trackedHead() {
        return git("rev-parse", "HEAD").strip();
    }

    private String status() {
        return git("status", "--porcelain");
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(repo.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static FileChange change(GitChangeSet changes, String path) {
        return changes.files().stream()
                .filter(file -> file.path().equals(path))
                .findFirst()
                .orElseThrow();
    }

    private String git(String... args) {
        List<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.addAll(List.of(args));
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(repo.toFile());
            builder.redirectErrorStream(true);
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("git 命令超时: " + command);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("git 失败(" + process.exitValue() + "): "
                        + command + "\n" + output);
            }
            return output;
        } catch (IOException error) {
            throw new IllegalStateException("无法执行 git: " + command, error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 git 时被中断", error);
        }
    }
}
