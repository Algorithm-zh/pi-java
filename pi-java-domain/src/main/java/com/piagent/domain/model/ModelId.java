package com.piagent.domain.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模型档案的逻辑标识。
 *
 * <p>只允许小写字母、数字、点、下划线与连字符，最长 64 个字符。
 * 与 {@code ModelRef} 不同，它不是发给 provider 的模型名，而是数据库与 API 使用的稳定 key。
 *
 * @param value 标识值，如 {@code deepseek-chat}
 */
public record ModelId(String value) {

    private static final Pattern PATTERN = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /**
     * 校验模型档案标识。
     *
     * @param value 标识值
     */
    public ModelId {
        Objects.requireNonNull(value, "modelId 不能为空");
        value = value.trim();
        Guard.require(PATTERN.matcher(value).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "modelId 只能包含小写字母、数字、点、下划线与连字符，且最长 64 个字符: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
