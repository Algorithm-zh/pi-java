package com.piagent.plugin.api;

import java.time.Duration;

/**
 * 插件工具定义。
 *
 * <p>{@code name} 是插件内短名，完整名称由宿主拼接为 {@code pluginId.name}；
 * 短名只允许小写字母、数字、下划线与连字符，避免二次前缀与歧义。</p>
 *
 * @param name 短名
 * @param description 给模型看的能力描述
 * @param inputSchemaJson JSON Schema 对象文本
 * @param risk 风险级别
 * @param timeout 单次执行超时
 * @param parallelSafe 是否允许与其他并行工具同批执行
 */
public record ToolSpec(String name, String description, String inputSchemaJson, PluginRisk risk,
        Duration timeout, boolean parallelSafe) {

    private static final String NAME_PATTERN = "[a-z][a-z0-9_-]{0,63}";
    private static final int MAX_DESCRIPTION_LENGTH = 1_024;
    private static final int MAX_SCHEMA_LENGTH = 20_000;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 校验工具定义。
     *
     * @param name 短名
     * @param description 描述
     * @param inputSchemaJson 参数 schema
     * @param risk 风险
     * @param timeout 超时
     * @param parallelSafe 是否可并行
     */
    public ToolSpec {
        if (name == null || !name.matches(NAME_PATTERN)) {
            throw new IllegalArgumentException("工具短名必须匹配 " + NAME_PATTERN + ": " + name);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("工具描述不能为空");
        }
        description = description.trim();
        if (description.length() > MAX_DESCRIPTION_LENGTH) {
            throw new IllegalArgumentException(
                    "工具描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        }
        if (inputSchemaJson == null || inputSchemaJson.isBlank()) {
            throw new IllegalArgumentException("工具参数 schema 不能为空");
        }
        if (inputSchemaJson.length() > MAX_SCHEMA_LENGTH) {
            throw new IllegalArgumentException("工具参数 schema 不能超过 " + MAX_SCHEMA_LENGTH + " 个字符");
        }
        if (risk == null) {
            throw new IllegalArgumentException("工具风险不能为空");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("工具超时必须为正数");
        }
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("工具超时不能超过 10 分钟");
        }
    }
}
