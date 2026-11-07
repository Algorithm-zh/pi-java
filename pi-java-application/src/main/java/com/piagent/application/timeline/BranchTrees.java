package com.piagent.application.timeline;

import com.piagent.domain.session.model.SessionEntry;
import com.piagent.domain.session.model.payload.BranchSummaryPayload;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import java.util.List;

/**
 * 从分支 entry 中提取工作区树证据。
 *
 * <p>分支状态以位置更晚的 entry 为准，因此从叶子向前扫描，第一个非空树即答案。
 * 同一位置只可能有一种 payload，不存在跨类型的全局优先级。</p>
 */
final class BranchTrees {

    private BranchTrees() {
    }

    /**
     * 返回分支上最后一个可用的工作区树。
     *
     * @param branch 从根到叶子的 entry
     * @return 树对象；没有任何证据时为 null
     */
    static String treeOf(List<SessionEntry> branch) {
        for (int index = branch.size() - 1; index >= 0; index--) {
            String tree = treeOf(branch.get(index));
            if (tree != null && !tree.isBlank()) {
                return tree;
            }
        }
        return null;
    }

    private static String treeOf(SessionEntry entry) {
        Object payload = entry.payload();
        if (payload instanceof GitChangeSetPayload change) {
            return change.treeSha();
        }
        if (payload instanceof GitCheckpointPayload checkpoint) {
            return checkpoint.baseTreeSha();
        }
        if (payload instanceof BranchSummaryPayload summary) {
            Object tree = summary.details().get("toTreeSha");
            return tree == null ? null : String.valueOf(tree);
        }
        return null;
    }
}
