package com.piagent.domain.tool.model;

/**
 * 工具风险级别。
 *
 * <p>风险只描述“最坏情况下能造成什么影响”，不直接等于审批策略：
 * 治理上下文会结合用户/工作区规则把风险解析成 ALLOW / ASK / DENY。</p>
 */
public enum ToolRisk {

    /** 只读、无副作用。 */
    LOW,

    /** 有限的本地副作用或资源消耗。 */
    MEDIUM,

    /** 可修改工作区文件或执行命令。 */
    HIGH,

    /** 可影响工作区之外或不可逆的操作。 */
    CRITICAL
}
