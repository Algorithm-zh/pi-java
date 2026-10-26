package com.piagent.trigger.http.plugin;

import com.piagent.domain.extension.model.PluginInstallation;
import java.time.Instant;

/**
 * 插件安装记录响应。
 *
 * <p>不返回配置原文与完整校验和：桌面 UI 只需要展示安装事实与状态。
 * 名称与描述来自 jar 清单，由控制器在读取安装记录时补全；清单不可读时
 * 退回插件标识，保证管理页仍可用。</p>
 *
 * @param id 插件标识
 * @param name 清单中的展示名称
 * @param description 清单中的描述
 * @param version 插件版本
 * @param apiVersion API 版本
 * @param jarPath jar 路径
 * @param status 状态
 * @param lastError 最近错误
 * @param installedAt 安装时间
 * @param updatedAt 更新时间
 */
public record PluginResponse(String id, String name, String description, String version,
        String apiVersion, String jarPath, String status, String lastError, Instant installedAt,
        Instant updatedAt) {

    /**
     * 从领域聚合转换。
     *
     * @param installation 安装聚合
     * @param name 清单展示名称，可为 null
     * @param description 清单描述，可为 null
     * @return 响应
     */
    public static PluginResponse from(PluginInstallation installation, String name,
            String description) {
        return new PluginResponse(installation.id().value(), name, description,
                installation.artifact().version(), installation.artifact().apiVersion().text(),
                installation.artifact().jarPath().toString(), installation.status().name(),
                installation.lastError(), installation.installedAt(), installation.updatedAt());
    }
}
