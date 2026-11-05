package com.piagent.infrastructure.git;

import java.nio.file.Path;
import java.util.Map;

/**
 * 检查点保活 ref 的 git CLI 实现。
 *
 * <p>为树对象创建一个只存在于 {@code refs/pi/checkpoints/} 命名空间的内部 commit，
 * 不移动 HEAD、不创建用户可见分支，也不写用户索引。所有失败都只记日志。</p>
 */
final class GitCheckpointPins {

    private static final System.Logger LOG = System.getLogger(GitCheckpointPins.class.getName());

    private static final Map<String, String> IDENTITY = Map.of(
            "GIT_AUTHOR_NAME", "pi-java",
            "GIT_AUTHOR_EMAIL", "pi-java@localhost",
            "GIT_COMMITTER_NAME", "pi-java",
            "GIT_COMMITTER_EMAIL", "pi-java@localhost");

    private static final String PREFIX = "refs/pi/checkpoints/";

    private final GitCommandRunner runner;

    GitCheckpointPins(GitCommandRunner runner) {
        this.runner = runner;
    }

    void pin(Path root, String namespace, String checkpointId, String treeSha) {
        GitCommandRunner.Result commit = runner.run(root, IDENTITY,
                "commit-tree", treeSha, "-m", "pi checkpoint " + checkpointId);
        if (!commit.success()) {
            LOG.log(System.Logger.Level.WARNING, "创建检查点 commit 失败: {0}", commit.errorLine());
            return;
        }
        GitCommandRunner.Result ref = runner.run(root, Map.of(), "update-ref",
                PREFIX + namespace + "/" + checkpointId, commit.out());
        if (!ref.success()) {
            LOG.log(System.Logger.Level.WARNING, "写入检查点 ref 失败: {0}", ref.errorLine());
        }
    }

    void clear(Path root, String namespace) {
        String prefix = PREFIX + namespace + "/";
        GitCommandRunner.Result refs = runner.run(root, Map.of(),
                "for-each-ref", "--format=%(refname)", prefix);
        if (!refs.success()) {
            LOG.log(System.Logger.Level.WARNING, "列出检查点 ref 失败: {0}", refs.errorLine());
            return;
        }
        for (String ref : refs.out().split("\\R")) {
            if (ref.isBlank()) {
                continue;
            }
            GitCommandRunner.Result deleted = runner.run(root, Map.of(),
                    "update-ref", "-d", ref.strip());
            if (!deleted.success()) {
                LOG.log(System.Logger.Level.WARNING, "删除检查点 ref 失败: {0}",
                        deleted.errorLine());
            }
        }
    }
}
