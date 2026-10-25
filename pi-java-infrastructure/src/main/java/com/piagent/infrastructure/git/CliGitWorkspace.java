package com.piagent.infrastructure.git;

import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.infrastructure.config.GitSettings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 基于本机 git 可执行文件的 {@link WorkspaceGit} 实现。
 *
 * <p>快照通过临时索引把工作区（含未跟踪文件、遵守 .gitignore）写成树对象；
 * 不创建分支、不移动 HEAD、不写用户索引。撤销只还原本轮改动过的路径，
 * 并在写入前逐个校验工作区是否仍等于本轮结束时的内容。</p>
 */
public class CliGitWorkspace implements WorkspaceGit {

    private static final System.Logger LOG = System.getLogger(CliGitWorkspace.class.getName());

    // 快照与还原都绕过换行转换，保证存进临时索引的字节与工作区一致。
    private static final String AUTOCRLF_OFF = "core.autocrlf=false";

    private final GitCommandRunner runner;
    private final int maxDiffChars;

    /**
     * 创建适配器。
     *
     * @param settings Git 配置
     */
    public CliGitWorkspace(GitSettings settings) {
        this.runner = new GitCommandRunner(Duration.ofSeconds(settings.timeoutSeconds()));
        this.maxDiffChars = settings.maxDiffChars();
    }

    @Override
    public Optional<GitSnapshot> snapshot(Path workspace) {
        Path root = repositoryRoot(workspace);
        if (root == null) {
            return Optional.empty();
        }
        try (GitTempIndex index = GitTempIndex.create()) {
            String head = headSha(root);
            GitCommandRunner.Result treeResult = head == null
                    ? runner.run(root, index.environment(), "read-tree", "--empty")
                    : runner.run(root, index.environment(), "read-tree", head);
            requireSuccess(treeResult, "git read-tree");
            GitCommandRunner.Result add = runner.run(root, index.environment(),
                    "-c", AUTOCRLF_OFF, "add", "-A", "--ignore-errors", "--", ".");
            if (!add.success()) {
                LOG.log(System.Logger.Level.DEBUG, "git add 部分文件失败（已忽略）: {0}",
                        add.errorLine());
            }
            GitCommandRunner.Result write = runner.run(root, index.environment(), "write-tree");
            requireSuccess(write, "git write-tree");
            return Optional.of(new GitSnapshot(write.out(), head, branchName(root)));
        }
    }

    @Override
    public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
        Path root = repositoryRoot(workspace);
        if (root == null) {
            throw new DomainException(DomainErrorCode.GIT_UNAVAILABLE, "工作目录不是 Git 仓库");
        }
        GitCommandRunner.Result status = runner.run(root, Map.of(),
                "diff-tree", "--no-commit-id", "-r", "--no-renames", "--name-status", "-z",
                baseTreeSha, treeSha);
        requireSuccess(status, "git diff-tree --name-status");
        GitCommandRunner.Result numstat = runner.run(root, Map.of(),
                "diff-tree", "--no-commit-id", "-r", "--no-renames", "--numstat", "-z",
                baseTreeSha, treeSha);
        requireSuccess(numstat, "git diff-tree --numstat");
        List<FileChange> files = GitDiffParser.merge(status.stdout(), numstat.stdout());
        GitCommandRunner.Result diff = runner.run(root, Map.of(),
                "diff", "--no-color", "--no-ext-diff", "--no-textconv", "--no-renames", "-U3",
                baseTreeSha, treeSha);
        requireSuccess(diff, "git diff");
        String text = diff.out();
        boolean truncated = diff.truncated() || text.length() > maxDiffChars;
        if (text.length() > maxDiffChars) {
            text = text.substring(0, maxDiffChars) + "\n…（diff 已截断）";
        }
        return new GitChangeSet(baseTreeSha, treeSha, files, text, truncated);
    }

    @Override
    public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
        Path root = repositoryRoot(workspace);
        if (root == null) {
            throw new DomainException(DomainErrorCode.GIT_UNAVAILABLE, "工作目录不是 Git 仓库");
        }
        GitSnapshot current = snapshot(root)
                .orElseThrow(() -> new DomainException(DomainErrorCode.GIT_UNAVAILABLE,
                        "无法读取当前工作区快照"));
        List<FileChange> changed = changedPaths(root, baseTreeSha, treeSha);
        if (changed.isEmpty()) {
            return new GitRestoreResult(List.of(), List.of());
        }
        List<GitConflict> conflicts = conflicts(root, changed, current.treeSha(), treeSha);
        if (!conflicts.isEmpty()) {
            return new GitRestoreResult(List.of(), conflicts);
        }
        List<String> restored = apply(root, baseTreeSha, changed);
        return new GitRestoreResult(restored, List.of());
    }

    private List<FileChange> changedPaths(Path root, String baseTreeSha, String treeSha) {
        GitCommandRunner.Result status = runner.run(root, Map.of(),
                "diff-tree", "--no-commit-id", "-r", "--no-renames", "--name-status", "-z",
                baseTreeSha, treeSha);
        requireSuccess(status, "git diff-tree --name-status");
        return GitDiffParser.nameStatus(status.stdout());
    }

    private List<GitConflict> conflicts(Path root, List<FileChange> changed, String currentTree,
            String targetTree) {
        List<GitConflict> conflicts = new ArrayList<>();
        for (FileChange change : changed) {
            String currentBlob = blobAt(root, currentTree, change.path());
            String targetBlob = blobAt(root, targetTree, change.path());
            if (!Objects.equals(currentBlob, targetBlob)) {
                conflicts.add(new GitConflict(change.path(),
                        "本轮之后工作区又发生了变化，为避免覆盖已跳过"));
            }
        }
        return conflicts;
    }

    private List<String> apply(Path root, String baseTreeSha, List<FileChange> changed) {
        Set<String> restored = new LinkedHashSet<>();
        List<String> toCheckout = new ArrayList<>();
        for (FileChange change : changed) {
            String baseBlob = blobAt(root, baseTreeSha, change.path());
            if (baseBlob == null) {
                deleteWorktreeFile(root, change.path());
            } else {
                toCheckout.add(change.path());
            }
            restored.add(change.path());
        }
        if (!toCheckout.isEmpty()) {
            checkout(root, baseTreeSha, toCheckout);
        }
        return new ArrayList<>(restored);
    }

    private void checkout(Path root, String baseTreeSha, List<String> paths) {
        try (GitTempIndex index = GitTempIndex.create()) {
            requireSuccess(runner.run(root, index.environment(), "read-tree", baseTreeSha),
                    "git read-tree");
            for (String path : paths) {
                GitCommandRunner.Result result = runner.run(root, index.environment(),
                        "-c", AUTOCRLF_OFF, "checkout-index", "-f", "--", path);
                requireSuccess(result, "git checkout-index " + path);
            }
        }
    }

    private void deleteWorktreeFile(Path root, String path) {
        try {
            Files.deleteIfExists(safePath(root, path));
        } catch (IOException error) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "删除文件失败: " + path, error);
        }
    }

    private Path safePath(Path root, String path) {
        try {
            Path resolved = root.resolve(path).normalize();
            if (!resolved.startsWith(root)) {
                throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                        "拒绝操作仓库之外的路径: " + path);
            }
            return resolved;
        } catch (InvalidPathException error) {
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    "非法文件路径: " + path, error);
        }
    }

    private String blobAt(Path root, String treeSha, String path) {
        GitCommandRunner.Result result = runner.run(root, Map.of(),
                "rev-parse", "--verify", "--quiet", treeSha + ":" + path);
        return result.success() && !result.out().isBlank() ? result.out() : null;
    }

    private Path repositoryRoot(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            return null;
        }
        GitCommandRunner.Result result;
        try {
            result = runner.run(workspace, Map.of(), "rev-parse", "--show-toplevel");
        } catch (DomainException error) {
            if (error.code() == DomainErrorCode.GIT_UNAVAILABLE) {
                return null;
            }
            throw error;
        }
        if (!result.success() || result.out().isBlank()) {
            return null;
        }
        return Path.of(result.out());
    }

    private String headSha(Path root) {
        GitCommandRunner.Result result = runner.run(root, Map.of(),
                "rev-parse", "--verify", "--quiet", "HEAD");
        return result.success() && !result.out().isBlank() ? result.out() : null;
    }

    private String branchName(Path root) {
        GitCommandRunner.Result result = runner.run(root, Map.of(),
                "symbolic-ref", "--quiet", "--short", "HEAD");
        return result.success() && !result.out().isBlank() ? result.out() : null;
    }

    private static void requireSuccess(GitCommandRunner.Result result, String command) {
        if (!result.success()) {
            String detail = result.errorLine().isBlank() ? "退出码 " + result.exitCode()
                    : result.errorLine();
            throw new DomainException(DomainErrorCode.GIT_OPERATION_FAILED,
                    command + " 失败: " + detail);
        }
    }
}
