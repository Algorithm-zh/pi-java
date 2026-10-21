package com.piagent.domain.governance.model;

/**
 * 策略作用域。
 *
 * <p>WORKSPACE 规则只对匹配的工作区生效；USER 规则使用
 * {@link WorkspaceKey#GLOBAL}，对所有工作区生效。解析时 WORKSPACE 优先于 USER。</p>
 */
public enum PolicyScope {

    /** 用户级规则，跨工作区。 */
    USER,

    /** 工作区级规则。 */
    WORKSPACE
}
