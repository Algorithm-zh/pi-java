package com.piagent.infrastructure.git;

import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import com.piagent.domain.git.port.WorkspaceGit;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 显式关闭 Git 检查点时使用的空实现。
 *
 * <p>快照返回空，调用方自然跳过检查点；撤销与 diff 明确报错，避免静默返回
 * “没有改动”这类误导性结果。</p>
 */
public class DisabledWorkspaceGit implements WorkspaceGit {

    @Override
    public Optional<GitSnapshot> snapshot(Path workspace) {
        return Optional.empty();
    }

    @Override
    public GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha) {
        throw disabled();
    }

    @Override
    public GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha) {
        throw disabled();
    }

    private DomainException disabled() {
        return new DomainException(DomainErrorCode.GIT_UNAVAILABLE,
                "Git 检查点已通过 pi.git.enabled=false 关闭");
    }
}
