package com.piagent.domain.git.model;

import java.util.List;

/**
 * 一次“把工作区还原到目标树”的只读计划。
 *
 * <p>只描述会改动哪些路径以及哪些路径冲突，不写工作区；调用方可以先展示预览，
 * 执行时再调用 {@code restore} 重新校验。</p>
 *
 * @param targetTreeSha 目标树，即 run 开始前的工作区状态
 * @param latestTreeSha 当前分支上最后已知的工作区树
 * @param files 目标树与最新树之间有变化的路径
 * @param conflicts 因工作区又发生变化而无法安全还原的路径
 */
public record GitRestorePlan(String targetTreeSha, String latestTreeSha,
        List<String> files, List<GitConflict> conflicts) {

    /**
     * 规范化列表。
     *
     * @param targetTreeSha 目标树
     * @param latestTreeSha 最新树
     * @param files 变化路径
     * @param conflicts 冲突
     */
    public GitRestorePlan {
        files = files == null ? List.of() : List.copyOf(files);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /**
     * 是否可以在不覆盖用户改动的前提下执行。
     *
     * @return true 表示没有冲突
     */
    public boolean restorable() {
        return conflicts.isEmpty();
    }
}
