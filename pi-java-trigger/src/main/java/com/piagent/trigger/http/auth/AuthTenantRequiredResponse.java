package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.TenantChoice;
import java.util.List;

/**
 * 需要选择空间时的登录响应。
 *
 * @param code 稳定错误码
 * @param message 可读消息
 * @param tenants 候选空间
 */
public record AuthTenantRequiredResponse(String code, String message,
        List<TenantChoiceResponse> tenants) {

    /**
     * 由候选空间构造。
     *
     * @param message 可读消息
     * @param choices 候选空间
     * @return 响应
     */
    public static AuthTenantRequiredResponse of(String message, List<TenantChoice> choices) {
        return new AuthTenantRequiredResponse("AUTH_TENANT_REQUIRED", message,
                choices.stream().map(TenantChoiceResponse::from).toList());
    }

    /**
     * 候选空间。
     *
     * @param slug 空间短名
     * @param name 空间名称
     * @param kind 空间类型
     */
    public record TenantChoiceResponse(String slug, String name, String kind) {

        static TenantChoiceResponse from(TenantChoice choice) {
            return new TenantChoiceResponse(choice.slug(), choice.name(), choice.kind());
        }
    }
}
