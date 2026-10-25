package com.piagent.domain.session.model.payload;

import com.piagent.domain.git.model.FileChange;
import com.piagent.domain.session.model.EntryId;
import com.piagent.domain.session.model.EntryType;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;

/**
 * 一轮工具执行后的工作区改动。
 *
 * @param runId run 标识
 * @param turn 模型轮次，从 1 开始
 * @param baseLeafId 本轮助手消息之前的叶子；用于展示与审计
 * @param baseTreeSha 本轮开始前的树对象
 * @param treeSha 本轮结束后的树对象
 * @param files 文件变化
 * @param diff 统一 diff 文本，可能被截断
 * @param truncated diff 是否被截断
 * @param suggestedCommit 提交信息建议；没有改动时为空字符串
 */
public record GitChangeSetPayload(
        String runId,
        int turn,
        EntryId baseLeafId,
        String baseTreeSha,
        String treeSha,
        List<FileChange> files,
        String diff,
        boolean truncated,
        String suggestedCommit) implements EntryPayload {

    /**
     * 校验改动集合。
     *
     * @param runId run ID
     * @param turn 轮次
     * @param baseLeafId 基础叶子
     * @param baseTreeSha 起点树
     * @param treeSha 终点树
     * @param files 文件
     * @param diff diff
     * @param truncated 是否截断
     * @param suggestedCommit 提交建议
     */
    public GitChangeSetPayload {
        Guard.notBlank(runId, DomainErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        Guard.require(turn >= 1, DomainErrorCode.INVALID_ARGUMENT, "turn 必须从 1 开始");
        Guard.notBlank(baseTreeSha, DomainErrorCode.INVALID_ARGUMENT, "baseTreeSha 不能为空");
        Guard.notBlank(treeSha, DomainErrorCode.INVALID_ARGUMENT, "treeSha 不能为空");
        files = files == null ? List.of() : List.copyOf(files);
        diff = diff == null ? "" : diff;
        suggestedCommit = suggestedCommit == null ? "" : suggestedCommit;
    }

    @Override
    public EntryType entryType() {
        return EntryType.GIT_CHANGES;
    }

    /**
     * 新增行总数。
     *
     * @return 新增行
     */
    public int additions() {
        return files.stream().mapToInt(FileChange::additions).sum();
    }

    /**
     * 删除行总数。
     *
     * @return 删除行
     */
    public int deletions() {
        return files.stream().mapToInt(FileChange::deletions).sum();
    }

    /**
     * 是否有文件变化。
     *
     * @return true 表示有改动
     */
    public boolean hasChanges() {
        return !files.isEmpty();
    }
}
