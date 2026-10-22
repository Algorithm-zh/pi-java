package com.piagent.domain.extension.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.nio.file.Path;
import java.util.Objects;

/**
 * 插件清单解析结果。
 *
 * <p>它来自 {@code META-INF/pi-plugin.json} 与 jar 校验，是“安装瞬间”的事实；
 * 聚合只持久化其中可重新验证的部分，名称与描述用于 API 展示。</p>
 *
 * @param id 插件标识
 * @param name 展示名称
 * @param version 版本
 * @param apiVersion API 版本
 * @param jarPath jar 路径
 * @param checksum SHA-256
 * @param mainClass 入口类
 * @param description 描述
 */
public record PluginPackage(PluginId id, String name, String version, ApiVersion apiVersion,
        Path jarPath, String checksum, String mainClass, String description) {

    /**
     * 校验清单结果。
     *
     * @param id 标识
     * @param name 名称
     * @param version 版本
     * @param apiVersion API 版本
     * @param jarPath jar 路径
     * @param checksum 校验和
     * @param mainClass 入口类
     * @param description 描述
     */
    public PluginPackage {
        Objects.requireNonNull(id, "插件标识不能为空");
        name = Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "插件名称不能为空");
        Guard.maxLength(name, 128, DomainErrorCode.INVALID_ARGUMENT, "插件名称不能超过 128 个字符");
        version = Guard.notBlank(version, DomainErrorCode.INVALID_ARGUMENT, "插件版本不能为空");
        Objects.requireNonNull(apiVersion, "apiVersion 不能为空");
        Objects.requireNonNull(jarPath, "jarPath 不能为空");
        checksum = Guard.notBlank(checksum, DomainErrorCode.INVALID_ARGUMENT, "插件校验和不能为空");
        mainClass = Guard.notBlank(mainClass, DomainErrorCode.INVALID_ARGUMENT, "入口类不能为空");
        description = description == null ? "" : description.strip();
        Guard.maxLength(description, 1_024, DomainErrorCode.INVALID_ARGUMENT,
                "插件描述不能超过 1024 个字符");
    }

    /**
     * 生成安装产物。
     *
     * @param configJson 配置 JSON
     * @return 安装产物
     */
    public PluginArtifact artifact(String configJson) {
        return new PluginArtifact(version, apiVersion, jarPath, checksum, configJson);
    }
}
