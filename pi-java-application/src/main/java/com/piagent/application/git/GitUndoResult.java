package com.piagent.application.git;

import com.piagent.domain.git.model.GitConflict;
import java.util.List;

/**
 * 撤销上一轮的结果。
 *
 * <p>{@code rewound} 为 false 表示存在冲突，工作区与对话都没有被修改：
 * 撤销要么整体生效，要么完全不动。</p>
 *
 * @param restored 已还原到检查点状态的路径
 * @param conflicts 冲突列表
 * @param rewound 会话分支是否已退回
 */
public record GitUndoResult(
        List<String> restored,
        List<GitConflict> conflicts,
        boolean rewound) {

    /**
     * 规范化列表。
     *
     * @param restored 还原路径
     * @param conflicts 冲突
     * @param rewound 是否退回分支
     */
    public GitUndoResult {
        restored = restored == null ? List.of() : List.copyOf(restored);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }
}
