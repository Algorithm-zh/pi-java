package com.piagent.application.plugin.command;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 安装插件命令。
 *
 * <p>jar 由调用方准备为宿主可读的本地文件；服务只读取并校验，不移动文件。
 * 配置 JSON 可以为空，领域层会规范化为 {@code {}}。</p>
 *
 * @param jarPath 插件 jar 路径
 * @param configJson 插件配置 JSON
 */
public record InstallPluginCommand(Path jarPath, String configJson) {

    /**
     * 校验命令。
     *
     * @param jarPath jar 路径
     * @param configJson 配置 JSON
     */
    public InstallPluginCommand {
        Objects.requireNonNull(jarPath, "jarPath 不能为空");
    }
}
