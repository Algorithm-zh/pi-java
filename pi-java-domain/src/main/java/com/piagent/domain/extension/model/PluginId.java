package com.piagent.domain.extension.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.tool.model.ToolName;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 插件标识。
 *
 * <p>形如 {@code com.example.hello}，全局唯一；插件工具名必须以其为前缀，
 * 因此标识本身也要满足工具名前缀的字符约束。为了与 Maven 坐标、目录名和
 * 工具名前缀同时兼容，只允许小写字母、数字、点与连字符。</p>
 *
 * @param value 标识文本
 */
public record PluginId(String value) {

    private static final int MAX_LENGTH = 128;
    private static final Pattern PATTERN = Pattern.compile("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*");

    /**
     * 校验插件标识。
     *
     * @param value 标识文本
     */
    public PluginId {
        Objects.requireNonNull(value, "插件标识不能为空");
        value = Guard.notBlank(value, DomainErrorCode.INVALID_ARGUMENT, "插件标识不能为空");
        Guard.maxLength(value, MAX_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "插件标识不能超过 " + MAX_LENGTH + " 个字符");
        Guard.require(PATTERN.matcher(value).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "插件标识只能包含小写字母、数字、点与连字符: " + value);
    }

    /**
     * 生成插件工具的完整名称。
     *
     * @param toolName 插件内短名
     * @return 完整工具名
     */
    public ToolName toolName(String toolName) {
        return new ToolName(value + "." + toolName);
    }

    /**
     * 判断工具名是否属于本插件。
     *
     * @param toolName 完整工具名
     * @return true 表示带本插件前缀
     */
    public boolean owns(ToolName toolName) {
        return toolName.value().startsWith(value + ".");
    }

    @Override
    public String toString() {
        return value;
    }
}
