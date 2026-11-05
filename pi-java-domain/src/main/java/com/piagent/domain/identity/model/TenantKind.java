package com.piagent.domain.identity.model;

/**
 * 空间类型。
 *
 * <p>默认空间是升级与系统内置空间；个人空间在账号创建时自动生成，不能删除；
 * 团队空间由任意登录用户创建，创建者成为管理员。</p>
 */
public enum TenantKind {

    /** 系统默认空间。 */
    DEFAULT,

    /** 团队空间。 */
    TEAM,

    /** 个人空间。 */
    PERSONAL
}
