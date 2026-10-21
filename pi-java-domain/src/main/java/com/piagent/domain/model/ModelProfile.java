package com.piagent.domain.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 模型档案聚合根。
 *
 * <p>档案是用户可管理的模型配置；它不包含任何密钥，只引用环境变量名。
 * 更新时保留 {@code version}，由仓储用旧版本做乐观锁，成功后应用层再调用
 * {@link #withVersion(long)} 同步内存版本。</p>
 *
 * @param id 档案标识
 * @param displayName 展示名
 * @param endpoint 接入端点
 * @param enabled 是否启用
 * @param version 乐观锁版本，从 1 开始
 */
public record ModelProfile(ModelId id, String displayName, ModelEndpoint endpoint,
        boolean enabled, long version) {

    /** 新建档案的初始版本。 */
    public static final long INITIAL_VERSION = 1;
    private static final int MAX_DISPLAY_NAME_LENGTH = 128;

    /**
     * 校验模型档案。
     *
     * @param id 档案标识
     * @param displayName 展示名
     * @param endpoint 端点
     * @param enabled 是否启用
     * @param version 版本
     */
    public ModelProfile {
        Objects.requireNonNull(id, "id 不能为空");
        displayName = Guard.notBlank(displayName, DomainErrorCode.INVALID_ARGUMENT, "displayName 不能为空");
        Guard.maxLength(displayName, MAX_DISPLAY_NAME_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "displayName 不能超过 " + MAX_DISPLAY_NAME_LENGTH + " 个字符");
        Objects.requireNonNull(endpoint, "endpoint 不能为空");
        Guard.require(version >= INITIAL_VERSION, DomainErrorCode.INVALID_ARGUMENT, "version 必须从 1 开始");
    }

    /**
     * 创建启用状态的新档案。
     *
     * @param id 档案标识
     * @param displayName 展示名
     * @param endpoint 端点
     * @return 新档案
     */
    public static ModelProfile create(ModelId id, String displayName, ModelEndpoint endpoint) {
        return new ModelProfile(id, displayName, endpoint, true, INITIAL_VERSION);
    }

    /**
     * 重命名档案。
     *
     * @param newDisplayName 新展示名
     * @return 新档案实例
     */
    public ModelProfile rename(String newDisplayName) {
        return new ModelProfile(id, newDisplayName, endpoint, enabled, version);
    }

    /**
     * 替换接入端点。
     *
     * @param newEndpoint 新端点
     * @return 新档案实例
     */
    public ModelProfile updateEndpoint(ModelEndpoint newEndpoint) {
        return new ModelProfile(id, displayName, newEndpoint, enabled, version);
    }

    /**
     * 启用档案。
     *
     * @return 新档案实例
     */
    public ModelProfile enable() {
        return new ModelProfile(id, displayName, endpoint, true, version);
    }

    /**
     * 停用档案。停用不影响已存在的会话，运行时由选择策略拒绝新请求。
     *
     * @return 新档案实例
     */
    public ModelProfile disable() {
        return new ModelProfile(id, displayName, endpoint, false, version);
    }

    /**
     * 同步持久化版本。仅供仓储更新成功后调用。
     *
     * @param newVersion 新版本
     * @return 新档案实例
     */
    public ModelProfile withVersion(long newVersion) {
        Guard.require(newVersion >= version, DomainErrorCode.INVALID_ARGUMENT, "版本不能回退");
        return new ModelProfile(id, displayName, endpoint, enabled, newVersion);
    }

    /**
     * 返回逻辑模型引用。
     *
     * @return provider + modelName
     */
    public ModelRef ref() {
        return endpoint.ref();
    }
}
