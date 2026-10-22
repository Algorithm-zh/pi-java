package com.piagent.domain.extension.model;

/**
 * 插件安装状态。
 *
 * <p>{@code INSTALLED} 表示已校验并落库但未加载；{@code ACTIVE} 表示运行时已注册；
 * {@code DISABLED} 表示曾被显式停用；{@code FAILED} 保留最近一次加载错误，允许再次启用重试。</p>
 */
public enum PluginStatus {

    /** 已安装，未加载。 */
    INSTALLED,

    /** 已加载并可被工具目录发现。 */
    ACTIVE,

    /** 已停用，不再出现在新快照中。 */
    DISABLED,

    /** 最近一次加载或启动失败。 */
    FAILED
}
