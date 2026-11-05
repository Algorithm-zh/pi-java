package com.piagent.domain.git.port;

import com.piagent.domain.git.model.GitChangeSet;
import com.piagent.domain.git.model.GitRestorePlan;
import com.piagent.domain.git.model.GitRestoreResult;
import com.piagent.domain.git.model.GitSnapshot;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 工作区 Git 能力端口。
 *
 * <p>约束：实现方不得修改用户的分支、HEAD 或索引，也不得自动提交。快照通过
 * 临时索引写入仓库对象库，撤销只触碰本轮改动过的路径。</p>
 */
public interface WorkspaceGit {

    /**
     * 捕获工作区快照。
     *
     * @param workspace 会话工作目录
     * @return 快照；不是 Git 仓库或缺少 git 可执行文件时为空
     */
    Optional<GitSnapshot> snapshot(Path workspace);

    /**
     * 计算两个快照之间的变化。
     *
     * @param workspace 工作目录
     * @param baseTreeSha 起点树
     * @param treeSha 终点树
     * @return 变化集合；没有变化时 files 为空
     */
    GitChangeSet changes(Path workspace, String baseTreeSha, String treeSha);

    /**
     * 把 {@code baseTreeSha} 到 {@code treeSha} 之间改动过的路径还原到起点。
     *
     * <p>如果工作区当前内容已不等于 {@code treeSha}（用户在本轮之后又改过），
     * 该文件进入冲突列表且不写入；存在冲突时整个还原不执行。</p>
     *
     * @param workspace 工作目录
     * @param baseTreeSha 起点树
     * @param treeSha 终点树
     * @return 还原结果
     */
    GitRestoreResult restore(Path workspace, String baseTreeSha, String treeSha);

    /**
     * 计算把工作区还原到起点树的计划。
     *
     * <p>不修改工作区，也不修改用户索引；适配器可能为此创建临时对象。</p>
     *
     * <p>默认实现表示该 Git 适配器不支持预览，保持既有测试替身可编译；
     * 生产实现必须覆盖。</p>
     *
     * @param workspace 工作目录
     * @param baseTreeSha 目标树
     * @param treeSha 当前分支上最后已知的树
     * @return 还原计划
     */
    default GitRestorePlan planRestore(Path workspace, String baseTreeSha, String treeSha) {
        throw new UnsupportedOperationException("当前 Git 实现不支持还原计划");
    }

    /**
     * 为检查点创建内部保活 ref，避免树对象被 {@code git gc} 清理。
     *
     * <p>默认不保活，适配器可覆盖。</p>
     *
     * @param workspace 工作目录
     * @param namespace 命名空间，通常为会话 ID
     * @param checkpointId 检查点标识，通常为 run ID
     * @param treeSha 需要保活的树
     */
    default void pinCheckpoint(Path workspace, String namespace, String checkpointId,
            String treeSha) {
    }

    /**
     * 删除某个命名空间下的全部保活 ref。
     *
     * @param workspace 工作目录
     * @param namespace 命名空间
     */
    default void clearCheckpoints(Path workspace, String namespace) {
    }
}
