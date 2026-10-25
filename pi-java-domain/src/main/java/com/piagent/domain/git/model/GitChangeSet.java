package com.piagent.domain.git.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;

/**
 * 两个快照之间的变化集合。
 *
 * @param baseTreeSha 起点树对象
 * @param treeSha 终点树对象
 * @param files 文件变化列表
 * @param diff 统一 diff 文本；超出上限时截断
 * @param truncated diff 是否被截断
 */
public record GitChangeSet(
        String baseTreeSha,
        String treeSha,
        List<FileChange> files,
        String diff,
        boolean truncated) {

    /**
     * 校验变化集合。
     *
     * @param baseTreeSha 起点树
     * @param treeSha 终点树
     * @param files 文件列表
     * @param diff diff 文本
     * @param truncated 是否截断
     */
    public GitChangeSet {
        Guard.notBlank(baseTreeSha, DomainErrorCode.INVALID_ARGUMENT, "baseTreeSha 不能为空");
        Guard.notBlank(treeSha, DomainErrorCode.INVALID_ARGUMENT, "treeSha 不能为空");
        files = files == null ? List.of() : List.copyOf(files);
        diff = diff == null ? "" : diff;
    }

    /**
     * 是否没有变化。
     *
     * @return true 表示文件未变化
     */
    public boolean isEmpty() {
        return files.isEmpty();
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
}
