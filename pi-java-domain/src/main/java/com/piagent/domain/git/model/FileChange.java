package com.piagent.domain.git.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 单个文件的变化摘要。
 *
 * @param path 相对仓库根目录的路径，使用 Git 的斜杠分隔
 * @param kind 变化类型
 * @param additions 新增行数；二进制文件为 0
 * @param deletions 删除行数；二进制文件为 0
 * @param binary 是否为二进制文件
 */
public record FileChange(
        String path,
        GitChangeKind kind,
        int additions,
        int deletions,
        boolean binary) {

    /**
     * 校验变化摘要。
     *
     * @param path 路径
     * @param kind 变化类型
     * @param additions 新增行
     * @param deletions 删除行
     * @param binary 是否二进制
     */
    public FileChange {
        Guard.notBlank(path, DomainErrorCode.INVALID_ARGUMENT, "文件路径不能为空");
        Objects.requireNonNull(kind, "kind 不能为空");
        Guard.require(additions >= 0, DomainErrorCode.INVALID_ARGUMENT, "新增行数不能为负数");
        Guard.require(deletions >= 0, DomainErrorCode.INVALID_ARGUMENT, "删除行数不能为负数");
    }
}
