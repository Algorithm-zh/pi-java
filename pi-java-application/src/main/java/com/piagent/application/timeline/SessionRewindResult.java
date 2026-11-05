package com.piagent.application.timeline;

import com.piagent.domain.git.model.GitConflict;
import com.piagent.domain.session.model.EntryId;
import java.util.List;

/**
 * 一次回退请求的结果，预览与执行共用。
 *
 * <p>预览时 {@code dryRun=true, rewound=false}，{@code files} 是计划还原的路径；
 * 执行成功后 {@code files} 仍是计划路径，{@code restored} 才是真正写入的路径。
 * 冲突时 counts 为 0：没有任何 entry 被丢弃，调用方不应展示“已回退”文案。</p>
 *
 * @param dryRun 是否只是预览
 * @param rewound 是否已完成对话分支与工作区回退
 * @param restorable 工作区是否可以安全还原
 * @param files 计划还原的路径
 * @param restored 实际还原的路径
 * @param conflicts 冲突列表
 * @param briefEntryId 新写入的回溯简报 entry；预览或失败时为 null
 * @param discardedEntries 将丢弃的 entry 数
 * @param discardedRuns 将丢弃的 run 数
 * @param brief 简报正文；预览时也返回，供确认弹窗展示
 */
public record SessionRewindResult(boolean dryRun, boolean rewound, boolean restorable,
        List<String> files, List<String> restored, List<GitConflict> conflicts,
        EntryId briefEntryId, int discardedEntries, int discardedRuns, String brief) {

    /**
     * 规范化列表字段，避免调用方拿到 null。
     *
     * @param dryRun 是否只是预览
     * @param rewound 是否已完成回退
     * @param restorable 是否可以安全还原
     * @param files 计划还原的路径
     * @param restored 实际还原的路径
     * @param conflicts 冲突列表
     * @param briefEntryId 简报 entry
     * @param discardedEntries 丢弃的 entry 数
     * @param discardedRuns 丢弃的 run 数
     * @param brief 简报正文
     */
    public SessionRewindResult {
        files = files == null ? List.of() : List.copyOf(files);
        restored = restored == null ? List.of() : List.copyOf(restored);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        brief = brief == null ? "" : brief;
    }
}
