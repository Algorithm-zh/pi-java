package com.piagent.trigger.http.plugin;

import com.piagent.domain.extension.model.PluginInstallation;
import java.time.Instant;

/**
 * 插件安装记录响应。
 *
 * <p>不返回配置原文与完整校验和：桌面 UI 只需要展示安装事实与状态。
 * 名称与描述由插件运行时维护，重启后从 jar 清单重新读取，因此不在响应中伪造。</p>
 *
 * @param id 插件标识
 * @param version 插件版本
 * @param apiVersion API 版本
 * @param jarPath jar 路径
 * @param status 状态
 * @param lastError 最近错误
 * @param installedAt 安装时间
 * @param updatedAt 更新时间
 */
public record PluginResponse(String id, String version, String apiVersion, String jarPath,
        String status, String lastError, Instant installedAt, Instant updatedAt) {

    /**
     * 从领域聚合转换。
     *
     * @param installation 安装聚合
     * @return 响应
     */
    public static PluginResponse from(PluginInstallation installation) {
        return new PluginResponse(installation.id().value(), installation.artifact().version(),
                installation.artifact().apiVersion().text(),
                installation.artifact().jarPath().toString(), installation.status().name(),
                installation.lastError(), installation.installedAt(), installation.updatedAt());
    }
}
