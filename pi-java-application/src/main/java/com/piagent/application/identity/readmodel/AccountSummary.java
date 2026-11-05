package com.piagent.application.identity.readmodel;

import com.piagent.domain.identity.model.AppUser;

/**
 * 可被邀请进空间的账号摘要。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 */
public record AccountSummary(String id, String username, String displayName) {

    /**
     * 由账号构造。
     *
     * @param user 账号
     * @return 摘要
     */
    public static AccountSummary of(AppUser user) {
        return new AccountSummary(user.id().toString(), user.username(), user.displayName());
    }
}
