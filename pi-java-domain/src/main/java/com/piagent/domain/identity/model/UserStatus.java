package com.piagent.domain.identity.model;

/**
 * 账号状态。
 */
public enum UserStatus {

    /** 可登录。 */
    ACTIVE,

    /** 已停用，不能再登录或使用已有令牌。 */
    DISABLED
}
