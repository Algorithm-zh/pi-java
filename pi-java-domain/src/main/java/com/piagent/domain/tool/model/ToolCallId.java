package com.piagent.domain.tool.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 模型发起的工具调用标识。
 *
 * <p>与 {@link com.piagent.domain.session.model.message.ToolCallBlock#id()} 对应，
 * 用于把结果写回同一条 assistant 消息的调用位置。</p>
 *
 * @param value provider 返回的调用 ID
 */
public record ToolCallId(String value) {

    private static final int MAX_LENGTH = 64;

    /**
     * 校验调用 ID。
     *
     * @param value 调用 ID
     */
    public ToolCallId {
        Objects.requireNonNull(value, "toolCallId 不能为空");
        value = Guard.notBlank(value, DomainErrorCode.INVALID_ARGUMENT, "toolCallId 不能为空");
        Guard.maxLength(value, MAX_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "toolCallId 不能超过 " + MAX_LENGTH + " 个字符");
    }

    @Override
    public String toString() {
        return value;
    }
}
