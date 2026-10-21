package com.piagent.domain.governance.model;

/**
 * 工具访问策略效果。
 */
public enum PolicyEffect {

    /** 直接执行。 */
    ALLOW,

    /** 执行前必须获得用户批准。 */
    ASK,

    /** 拒绝执行，模型会收到 isError 结果。 */
    DENY
}
