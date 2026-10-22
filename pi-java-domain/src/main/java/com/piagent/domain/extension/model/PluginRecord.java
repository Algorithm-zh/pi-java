package com.piagent.domain.extension.model;

import java.time.Instant;
import java.util.Objects;

/**
 * 插件安装记录快照。
 *
 * <p>只用于仓储往返与聚合重建；业务规则集中在 {@link PluginInstallation}。
 * 字段与 {@code pi_plugin} 表一一对应，避免持久化层反向依赖聚合内部状态。</p>
 *
 * @param id 插件标识
 * @param artifact 安装产物
 * @param status 状态
 * @param lastError 最近错误
 * @param installedAt 安装时间
 * @param updatedAt 更新时间
 */
public record PluginRecord(PluginId id, PluginArtifact artifact, PluginStatus status,
        String lastError, Instant installedAt, Instant updatedAt) {

    /**
     * 校验快照完整性。
     *
     * @param id 插件标识
     * @param artifact 安装产物
     * @param status 状态
     * @param lastError 最近错误
     * @param installedAt 安装时间
     * @param updatedAt 更新时间
     */
    public PluginRecord {
        Objects.requireNonNull(id, "插件标识不能为空");
        Objects.requireNonNull(artifact, "安装产物不能为空");
        Objects.requireNonNull(status, "插件状态不能为空");
        Objects.requireNonNull(installedAt, "安装时间不能为空");
        Objects.requireNonNull(updatedAt, "更新时间不能为空");
    }
}
