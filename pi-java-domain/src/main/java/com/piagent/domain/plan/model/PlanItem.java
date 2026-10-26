package com.piagent.domain.plan.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 一条待办。
 *
 * @param content 可验证的一步，最多 200 字
 * @param status 当前状态
 */
public record PlanItem(String content, PlanItemStatus status) {

    /** 单条待办的最大字数。 */
    public static final int MAX_CONTENT_LENGTH = 200;

    /**
     * 校验待办。
     *
     * @param content 内容
     * @param status 状态
     */
    public PlanItem {
        content = Guard.notBlank(content, DomainErrorCode.INVALID_ARGUMENT, "待办内容不能为空").strip();
        Guard.maxLength(content, MAX_CONTENT_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "待办内容不能超过 " + MAX_CONTENT_LENGTH + " 个字符");
        Objects.requireNonNull(status, "待办状态不能为空");
    }
}
