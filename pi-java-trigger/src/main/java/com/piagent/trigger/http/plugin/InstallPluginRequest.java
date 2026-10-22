package com.piagent.trigger.http.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/**
 * 安装插件请求。
 *
 * <p>{@code jarPath} 必须是宿主进程可读的本地路径；{@code config} 是 JSON 对象，
 * 省略时保存为空对象。服务端不信任路径中的任何内容，仍会重新读取 jar 校验。</p>
 *
 * @param jarPath 插件 jar 路径
 * @param config 插件配置对象
 */
public record InstallPluginRequest(String jarPath, JsonNode config) {

    /**
     * 校验请求。
     *
     * @param jarPath jar 路径
     * @param config 配置对象
     */
    public InstallPluginRequest {
        jarPath = Guard.notBlank(jarPath, DomainErrorCode.INVALID_ARGUMENT, "jarPath 不能为空");
        if (config != null && !config.isObject()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "config 必须是 JSON 对象");
        }
    }

    /**
     * 转换为本地路径。
     *
     * @return 路径
     */
    public Path path() {
        try {
            return Path.of(jarPath);
        } catch (InvalidPathException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "jarPath 不是合法路径: " + jarPath, error);
        }
    }

    /**
     * 配置 JSON 文本。
     *
     * @return JSON 文本，未提供时为 null
     */
    public String configJson() {
        return config == null ? null : config.toString();
    }
}
