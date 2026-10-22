package com.piagent.domain.extension.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 插件安装产物。
 *
 * <p>安装记录只保存“如何重新加载这个插件”的不可变信息：版本、jar 路径、
 * SHA-256 与配置 JSON。文件内容本身不复制，重新启用时按路径与校验和再次验证。</p>
 *
 * @param version 插件版本
 * @param apiVersion 宿主 API 版本
 * @param jarPath jar 绝对路径
 * @param checksum SHA-256 小写十六进制
 * @param configJson 插件配置 JSON
 */
public record PluginArtifact(String version, ApiVersion apiVersion, Path jarPath, String checksum,
        String configJson) {

    private static final int MAX_VERSION_LENGTH = 32;
    private static final int MAX_PATH_LENGTH = 1_024;
    private static final int MAX_CONFIG_LENGTH = 65_536;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    /**
     * 校验安装产物。
     *
     * @param version 插件版本
     * @param apiVersion API 版本
     * @param jarPath jar 路径
     * @param checksum SHA-256
     * @param configJson 配置 JSON
     */
    public PluginArtifact {
        version = Guard.notBlank(version, DomainErrorCode.INVALID_ARGUMENT, "插件版本不能为空");
        Guard.maxLength(version, MAX_VERSION_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "插件版本不能超过 " + MAX_VERSION_LENGTH + " 个字符");
        Objects.requireNonNull(apiVersion, "apiVersion 不能为空");
        Objects.requireNonNull(jarPath, "jarPath 不能为空");
        jarPath = jarPath.toAbsolutePath().normalize();
        Guard.maxLength(jarPath.toString(), MAX_PATH_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "插件 jar 路径不能超过 " + MAX_PATH_LENGTH + " 个字符");
        checksum = Guard.notBlank(checksum, DomainErrorCode.INVALID_ARGUMENT, "插件校验和不能为空")
                .toLowerCase(Locale.ROOT);
        Guard.require(SHA256.matcher(checksum).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "插件校验和必须是 64 位小写十六进制");
        configJson = configJson == null || configJson.isBlank() ? "{}" : configJson;
        Guard.maxLength(configJson, MAX_CONFIG_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "插件配置不能超过 " + MAX_CONFIG_LENGTH + " 个字符");
    }
}
