package com.piagent.domain.governance.model;

/**
 * 审批记忆模式。
 */
public enum ApprovalMode {

    /** 每次调用都需要审批。 */
    ONCE,

    /** 本会话内同类调用只审批一次。 */
    SESSION,

    /** 一直允许，直到用户显式撤销。 */
    ALWAYS
}
