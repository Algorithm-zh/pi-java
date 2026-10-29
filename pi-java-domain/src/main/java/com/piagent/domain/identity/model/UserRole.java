package com.piagent.domain.identity.model;

/**
 * 账号角色。
 *
 * <p>ADMIN 可以管理本租户之外的新租户与账号；MEMBER 只能访问所属租户的数据。</p>
 */
public enum UserRole {

    /** 服务管理员。 */
    ADMIN,

    /** 普通成员。 */
    MEMBER
}
