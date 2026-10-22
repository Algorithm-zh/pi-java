package com.piagent.infrastructure.persistence.extension;

import com.piagent.domain.extension.model.ApiVersion;
import com.piagent.domain.extension.model.PluginArtifact;
import com.piagent.domain.extension.model.PluginId;
import com.piagent.domain.extension.model.PluginInstallation;
import com.piagent.domain.extension.model.PluginRecord;
import com.piagent.domain.extension.model.PluginStatus;
import java.nio.file.Path;

/**
 * `pi_plugin` 行与插件安装聚合之间的转换。
 *
 * <p>数据库只存安装事实；转换时重新构造 {@link PluginArtifact}，让校验和、
 * 路径与配置再次经过领域校验，避免损坏数据静默进入运行时。</p>
 */
public final class PluginPoConverter {

    /**
     * 聚合转持久化对象。
     *
     * @param installation 安装聚合
     * @return 持久化对象
     */
    public PluginPO toPo(PluginInstallation installation) {
        PluginRecord record = installation.record();
        PluginPO po = new PluginPO();
        po.setId(record.id().value());
        po.setVersion(record.artifact().version());
        po.setApiVersion(record.artifact().apiVersion().text());
        po.setJarPath(record.artifact().jarPath().toString());
        po.setChecksum(record.artifact().checksum());
        po.setStatus(record.status().name());
        po.setConfigJson(record.artifact().configJson());
        po.setLastError(record.lastError());
        po.setInstalledAt(record.installedAt());
        po.setUpdatedAt(record.updatedAt());
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 安装聚合
     */
    public PluginInstallation toDomain(PluginPO po) {
        PluginArtifact artifact = new PluginArtifact(po.getVersion(),
                ApiVersion.parse(po.getApiVersion()), Path.of(po.getJarPath()),
                po.getChecksum(), po.getConfigJson());
        PluginRecord record = new PluginRecord(new PluginId(po.getId()), artifact,
                PluginStatus.valueOf(po.getStatus()), po.getLastError(), po.getInstalledAt(),
                po.getUpdatedAt());
        return PluginInstallation.reconstitute(record);
    }
}
