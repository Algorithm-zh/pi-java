package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.UserSummary;

/**
 * 账号响应。
 *
 * @param id 账号标识
 * @param username 登录名
 * @param displayName 展示名
 * @param role 角色
 * @param status 状态
 */
public record UserResponse(String id, String username, String displayName, String role,
        String status) {

    /**
     * 由读模型转换。
     *
     * @param summary 账号摘要
     * @return 响应
     */
    public static UserResponse from(UserSummary summary) {
        return new UserResponse(summary.id(), summary.username(), summary.displayName(),
                summary.role(), summary.status());
    }
}
