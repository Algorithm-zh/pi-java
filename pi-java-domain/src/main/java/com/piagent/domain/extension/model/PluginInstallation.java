package com.piagent.domain.extension.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import com.piagent.domain.shared.DomainException;
import java.time.Instant;
import java.util.Objects;

/**
 * 插件安装聚合根。
 *
 * <p>状态迁移：</p>
 * <pre>
 * install  -> INSTALLED
 * INSTALLED/DISABLED/FAILED --enable--> ACTIVE
 * ACTIVE --disable--> DISABLED
 * 任意非 ACTIVE --加载失败--> FAILED
 * </pre>
 *
 * <p>聚合只保存可验证的安装事实；jar 内容、ClassLoader 与工具注册属于运行时，
 * 由基础设施层的 {@code PluginHost} 维护。因此本类不引用 plugin-api 类型。</p>
 */
public final class PluginInstallation {

    private static final int MAX_ERROR_LENGTH = 1_024;

    private final PluginId id;
    private final PluginArtifact artifact;
    private final Instant installedAt;
    private PluginStatus status;
    private String lastError;
    private Instant updatedAt;

    private PluginInstallation(PluginId id, PluginArtifact artifact, PluginStatus status,
            String lastError, Instant installedAt, Instant updatedAt) {
        this.id = id;
        this.artifact = artifact;
        this.status = status;
        this.lastError = lastError;
        this.installedAt = installedAt;
        this.updatedAt = updatedAt;
    }

    /**
     * 创建新的安装记录。
     *
     * @param pkg 清单解析结果
     * @param configJson 配置 JSON
     * @param now 当前时间
     * @return 状态为 INSTALLED 的聚合
     */
    public static PluginInstallation install(PluginPackage pkg, String configJson, Instant now) {
        Objects.requireNonNull(pkg, "插件包不能为空");
        Objects.requireNonNull(now, "now 不能为空");
        return new PluginInstallation(pkg.id(), pkg.artifact(configJson), PluginStatus.INSTALLED,
                null, now, now);
    }

    /**
     * 从持久化记录重建聚合。
     *
     * @param record 记录
     * @return 聚合
     */
    public static PluginInstallation reconstitute(PluginRecord record) {
        Objects.requireNonNull(record, "record 不能为空");
        return new PluginInstallation(record.id(), record.artifact(), record.status(),
                record.lastError(), record.installedAt(), record.updatedAt());
    }

    /**
     * 启用成功后置为 ACTIVE。
     *
     * @param now 当前时间
     */
    public void activate(Instant now) {
        requireNow(now);
        Guard.require(status != PluginStatus.ACTIVE, DomainErrorCode.PLUGIN_INVALID_STATE,
                "插件已处于启用状态: " + id);
        status = PluginStatus.ACTIVE;
        lastError = null;
        updatedAt = now;
    }

    /**
     * 停用插件。
     *
     * @param now 当前时间
     */
    public void disable(Instant now) {
        requireNow(now);
        if (status != PluginStatus.ACTIVE) {
            throw new DomainException(DomainErrorCode.PLUGIN_INVALID_STATE,
                    "只有 ACTIVE 插件可以停用: " + id + " -> " + status);
        }
        status = PluginStatus.DISABLED;
        lastError = null;
        updatedAt = now;
    }

    /**
     * 记录加载失败。
     *
     * @param error 错误消息
     * @param now 当前时间
     */
    public void fail(String error, Instant now) {
        requireNow(now);
        status = PluginStatus.FAILED;
        String value = error == null || error.isBlank() ? "插件加载失败" : error;
        lastError = value.length() <= MAX_ERROR_LENGTH ? value : value.substring(0, MAX_ERROR_LENGTH);
        updatedAt = now;
    }

    /**
     * 导出持久化快照。
     *
     * @return 快照
     */
    public PluginRecord record() {
        return new PluginRecord(id, artifact, status, lastError, installedAt, updatedAt);
    }

    /**
     * 插件标识。
     *
     * @return 标识
     */
    public PluginId id() {
        return id;
    }

    /**
     * 安装产物。
     *
     * @return 产物
     */
    public PluginArtifact artifact() {
        return artifact;
    }

    /**
     * 当前状态。
     *
     * @return 状态
     */
    public PluginStatus status() {
        return status;
    }

    /**
     * 最近错误。
     *
     * @return 错误文本，可能为 null
     */
    public String lastError() {
        return lastError;
    }

    /**
     * 安装时间。
     *
     * @return 时间
     */
    public Instant installedAt() {
        return installedAt;
    }

    /**
     * 更新时间。
     *
     * @return 时间
     */
    public Instant updatedAt() {
        return updatedAt;
    }

    private static void requireNow(Instant now) {
        Objects.requireNonNull(now, "now 不能为空");
    }
}
