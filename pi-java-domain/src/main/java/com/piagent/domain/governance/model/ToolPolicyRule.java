package com.piagent.domain.governance.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 工具策略规则聚合根。
 *
 * <p>规则通过 {@link ToolPolicyKey} 定位，通过 {@link ToolPolicySettings} 决策；
 * 新规则 {@code id=0}，由数据库生成自增主键。更新保留原版本，仓储以旧版本做 CAS，
 * 成功后应用层调用 {@link #withVersion(long)}。</p>
 *
 * @param id 自增主键，0 表示未持久化
 * @param key 定位键
 * @param settings 决策设置
 * @param version 乐观锁版本，从 1 开始
 */
public record ToolPolicyRule(long id, ToolPolicyKey key, ToolPolicySettings settings, long version) {

    /** 新规则初始版本。 */
    public static final long INITIAL_VERSION = 1;

    /**
     * 校验规则。
     *
     * @param id 主键
     * @param key 定位键
     * @param settings 设置
     * @param version 版本
     */
    public ToolPolicyRule {
        Guard.require(id >= 0, DomainErrorCode.INVALID_ARGUMENT, "策略 id 不能为负数");
        Objects.requireNonNull(key, "key 不能为空");
        Objects.requireNonNull(settings, "settings 不能为空");
        Guard.require(version >= INITIAL_VERSION, DomainErrorCode.INVALID_ARGUMENT,
                "策略版本必须从 1 开始");
    }

    /**
     * 创建未持久化规则。
     *
     * @param key 定位键
     * @param settings 设置
     * @return 新规则
     */
    public static ToolPolicyRule create(ToolPolicyKey key, ToolPolicySettings settings) {
        return new ToolPolicyRule(0L, key, settings, INITIAL_VERSION);
    }

    /**
     * 替换决策设置。
     *
     * @param newSettings 新设置
     * @return 新规则实例
     */
    public ToolPolicyRule withSettings(ToolPolicySettings newSettings) {
        return new ToolPolicyRule(id, key, newSettings, version);
    }

    /**
     * 同步持久化版本。仅供仓储更新成功后调用。
     *
     * @param newVersion 新版本
     * @return 新规则实例
     */
    public ToolPolicyRule withVersion(long newVersion) {
        Guard.require(newVersion >= version, DomainErrorCode.INVALID_ARGUMENT, "版本不能回退");
        return new ToolPolicyRule(id, key, settings, newVersion);
    }
}
