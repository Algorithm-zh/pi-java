package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.AccountSummary;

/**
 * 可邀请账号响应，只暴露登录名与展示名。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 */
public record AccountResponse(String id, String username, String displayName) {

    /**
     * 由读模型转换。
     *
     * @param summary 账号摘要
     * @return 响应
     */
    public static AccountResponse from(AccountSummary summary) {
        return new AccountResponse(summary.id(), summary.username(), summary.displayName());
    }
}
