package com.piagent.domain.tool.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 工具名。
 *
 * <p>内置工具使用短名（如 {@code read}）；插件工具必须带插件前缀（如
 * {@code my-plugin.search}）。名称只允许小写字母、数字、点、下划线与连字符，
 * 且不允许以分隔符开头或结尾，便于稳定地出现在 LLM schema 与策略表中。</p>
 *
 * @param value 工具名
 */
public record ToolName(String value) {

    private static final int MAX_LENGTH = 128;
    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    /**
     * 校验工具名。
     *
     * @param value 工具名
     */
    public ToolName {
        Objects.requireNonNull(value, "工具名不能为空");
        value = value.trim();
        Guard.maxLength(value, MAX_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "工具名不能超过 " + MAX_LENGTH + " 个字符");
        Guard.require(PATTERN.matcher(value).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "工具名只能包含小写字母、数字、点、下划线与连字符: " + value);
    }

    @Override
    public String toString() {
        return value;
    }
}
