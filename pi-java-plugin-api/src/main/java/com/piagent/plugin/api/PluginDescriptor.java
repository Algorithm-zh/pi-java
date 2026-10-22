package com.piagent.plugin.api;

/**
 * 插件描述。
 *
 * <p>必须与 {@code META-INF/pi-plugin.json} 一致；宿主会核对 id、入口类与 API 版本，
 * 防止 jar 中的实现与清单声明不符。</p>
 *
 * @param id 插件标识
 * @param name 展示名称
 * @param version 插件版本
 * @param apiVersion 宿主 API 版本，主版本必须为 1
 * @param mainClass 实现 {@link PiPlugin} 的入口类
 * @param description 描述
 */
public record PluginDescriptor(PluginId id, String name, String version, String apiVersion,
        String mainClass, String description) {

    /**
     * 校验描述。
     *
     * @param id 标识
     * @param name 名称
     * @param version 版本
     * @param apiVersion API 版本
     * @param mainClass 入口类
     * @param description 描述
     */
    public PluginDescriptor {
        if (id == null) {
            throw new IllegalArgumentException("插件标识不能为空");
        }
        name = requireText(name, "插件名称", 128);
        version = requireText(version, "插件版本", 32);
        apiVersion = requireText(apiVersion, "API 版本", 16);
        mainClass = requireText(mainClass, "入口类", 256);
        description = description == null ? "" : description.strip();
        if (description.length() > 1_024) {
            throw new IllegalArgumentException("插件描述不能超过 1024 个字符");
        }
    }

    private static String requireText(String value, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        String text = value.trim();
        if (text.length() > maxLength) {
            throw new IllegalArgumentException(label + "不能超过 " + maxLength + " 个字符");
        }
        return text;
    }
}
