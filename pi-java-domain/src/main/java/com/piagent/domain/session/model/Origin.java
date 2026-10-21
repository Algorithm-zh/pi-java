package com.piagent.domain.session.model;

/**
 * 会话来源。
 */
public enum Origin {

    /** 用户直接创建。 */
    USER,

    /** 从已有会话 fork。 */
    FORK,

    /** 子智能体创建。 */
    SUBAGENT
}
