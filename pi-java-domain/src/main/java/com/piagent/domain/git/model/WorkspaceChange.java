package com.piagent.domain.git.model;

import java.util.List;

/**
 * 一次运行在某个工作区产生的全部改动。
 *
 * @param baseTreeSha 运行开始前的树对象
 * @param treeSha 最近一次记录的树对象
 * @param turns 逐轮改动
 */
public record WorkspaceChange(
        String baseTreeSha,
        String treeSha,
        List<GitChangeSet> turns) {

    /**
     * 规范化列表。
     *
     * @param baseTreeSha 起点树
     * @param treeSha 终点树
     * @param turns 逐轮改动
     */
    public WorkspaceChange {
        turns = turns == null ? List.of() : List.copyOf(turns);
    }

    /**
     * 是否没有改动。
     *
     * @return true 表示所有轮次都没有文件变化
     */
    public boolean isEmpty() {
        return turns.stream().allMatch(GitChangeSet::isEmpty);
    }
}
