package com.piagent.domain.git.model;

import java.util.List;

/**
 * 工作区还原结果。
 *
 * <p>存在冲突时不做任何写入：宁可保持现状，也不产生一半新一半旧的混合工作区。</p>
 *
 * @param restored 已还原到检查点状态的路径
 * @param conflicts 冲突列表
 */
public record GitRestoreResult(List<String> restored, List<GitConflict> conflicts) {

    /**
     * 规范化列表。
     *
     * @param restored 已还原路径
     * @param conflicts 冲突
     */
    public GitRestoreResult {
        restored = restored == null ? List.of() : List.copyOf(restored);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }

    /**
     * 是否完全还原。
     *
     * @return true 表示没有冲突
     */
    public boolean complete() {
        return conflicts.isEmpty();
    }
}
