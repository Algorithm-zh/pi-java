package com.piagent.plugin.api;

/**
 * 插件工具风险级别。
 *
 * <p>宿主按风险映射默认策略：LOW 放行，MEDIUM/HIGH 需要审批，CRITICAL 默认拒绝。
 * 插件应如实声明，不能借低风险绕过宿主治理。</p>
 */
public enum PluginRisk {

    /** 只读或纯计算。 */
    LOW,

    /** 可能产生副作用。 */
    MEDIUM,

    /** 写文件、执行命令等高风险操作。 */
    HIGH,

    /** 不可逆或影响系统范围的操作。 */
    CRITICAL
}
