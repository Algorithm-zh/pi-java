package com.piagent.domain.git.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 工作区在某一时刻的 Git 快照。
 *
 * <p>快照只记录树对象，不创建分支、不移动 HEAD、不修改用户索引；所有内容都能从
 * 仓库对象库恢复。{@code headSha} 与 {@code branch} 只用于展示，空仓库或游离
 * HEAD 时为空。</p>
 *
 * @param treeSha 工作区树对象（含未跟踪文件，忽略 .gitignore）
 * @param headSha 当时 HEAD 指向的提交；空仓库为 null
 * @param branch 当时分支名；游离 HEAD 或空仓库为 null
 */
public record GitSnapshot(String treeSha, String headSha, String branch) {

    /**
     * 校验快照。
     *
     * @param treeSha 树对象
     * @param headSha 提交
     * @param branch 分支
     */
    public GitSnapshot {
        Guard.notBlank(treeSha, DomainErrorCode.INVALID_ARGUMENT, "treeSha 不能为空");
    }

    /**
     * 是否基于已有提交。
     *
     * @return true 表示 HEAD 存在
     */
    public boolean hasHead() {
        return headSha != null && !headSha.isBlank();
    }
}
