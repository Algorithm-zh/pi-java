package com.piagent.domain.git.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 撤销时无法安全还原的文件。
 *
 * <p>典型原因是本轮之后用户又手动改了同一个文件；此时直接覆盖会丢掉用户的新改动，
 * 因此跳过该文件并在结果里报告。</p>
 *
 * @param path 冲突文件路径
 * @param reason 面向用户的原因
 */
public record GitConflict(String path, String reason) {

    /**
     * 校验冲突。
     *
     * @param path 路径
     * @param reason 原因
     */
    public GitConflict {
        Guard.notBlank(path, DomainErrorCode.INVALID_ARGUMENT, "冲突路径不能为空");
        Objects.requireNonNull(reason, "reason 不能为空");
    }
}
