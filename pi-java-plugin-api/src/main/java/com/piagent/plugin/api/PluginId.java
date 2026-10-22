package com.piagent.plugin.api;

/**
 * 插件标识契约。
 *
 * <p>与宿主内部标识保持相同的字符约束，但独立定义，保证插件 API 只依赖 JDK。
 * 形如 {@code com.example.hello}，全局唯一；插件工具名必须以其为前缀。</p>
 *
 * @param value 标识文本
 */
public record PluginId(String value) {

    private static final String PATTERN = "[a-z][a-z0-9]*(?:[.-][a-z0-9]+)*";
    private static final int MAX_LENGTH = 128;

    /**
     * 校验标识。
     *
     * @param value 标识文本
     */
    public PluginId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("插件标识不能为空");
        }
        value = value.trim();
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("插件标识不能超过 " + MAX_LENGTH + " 个字符");
        }
        if (!value.matches(PATTERN)) {
            throw new IllegalArgumentException("插件标识只能包含小写字母、数字、点与连字符: " + value);
        }
    }

    /**
     * 解析标识。
     *
     * @param raw 文本
     * @return 标识
     */
    public static PluginId parse(String raw) {
        return new PluginId(raw);
    }

    @Override
    public String toString() {
        return value;
    }
}
