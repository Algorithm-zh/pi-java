package com.piagent.application.git;

import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.session.model.payload.GitChangeSetPayload;
import com.piagent.domain.session.model.payload.GitCheckpointPayload;
import java.util.List;

/**
 * 最近一次 run 的工作区改动汇总。
 *
 * <p>只读取会话分支上的 Git entry，不访问磁盘；展示层据此渲染“本轮改动”
 * 面板，撤销用例据此找到检查点。</p>
 *
 * @param checkpoint 最近一次 run 的检查点；工作区不是 Git 仓库时为 null
 * @param turns 该 run 内逐轮改动，按发生顺序
 * @param canUndo 当前会话是否允许撤销（空闲且存在检查点）
 */
public record SessionGitChanges(
        GitCheckpointPayload checkpoint,
        List<GitChangeSetPayload> turns,
        boolean canUndo) {

    /** 没有任何 Git 信息。 */
    public static final SessionGitChanges EMPTY = new SessionGitChanges(null, List.of(), false);

    /**
     * 规范化列表。
     *
     * @param checkpoint 检查点
     * @param turns 逐轮改动
     * @param canUndo 是否可撤销
     */
    public SessionGitChanges {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /**
     * 是否有可展示的检查点。
     *
     * @return true 表示存在检查点
     */
    public boolean available() {
        return checkpoint != null;
    }

    /**
     * 本轮新增行总数。
     *
     * @return 新增行
     */
    public int additions() {
        return turns.stream().mapToInt(GitChangeSetPayload::additions).sum();
    }

    /**
     * 本轮删除行总数。
     *
     * @return 删除行
     */
    public int deletions() {
        return turns.stream().mapToInt(GitChangeSetPayload::deletions).sum();
    }

    /**
     * 本轮涉及的文件数（按路径去重）。
     *
     * @return 文件数
     */
    public int fileCount() {
        return (int) turns.stream()
                .flatMap(turn -> turn.files().stream())
                .map(FileChange::path)
                .distinct()
                .count();
    }
}
