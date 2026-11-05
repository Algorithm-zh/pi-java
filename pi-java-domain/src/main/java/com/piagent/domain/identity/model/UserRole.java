package com.piagent.domain.identity.model;

/**
 * 空间内的角色。
 *
 * <p>ADMIN 可以管理本空间的成员与删除空间；MEMBER 只能使用空间内的数据。
 * 角色按空间计算：同一个账号可以在自己的个人空间是管理员、在团队空间是成员。</p>
 */
public enum UserRole {

    /** 服务管理员。 */
    ADMIN,

    /** 普通成员。 */
    MEMBER
}
