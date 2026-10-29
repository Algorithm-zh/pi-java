package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.TenantSummary;

/**
 * 租户响应。
 *
 * @param id 租户标识
 * @param slug 短名
 * @param name 名称
 */
public record TenantResponse(String id, String slug, String name) {

    /**
     * 由读模型转换。
     *
     * @param summary 租户摘要
     * @return 响应
     */
    public static TenantResponse from(TenantSummary summary) {
        return new TenantResponse(summary.id(), summary.slug(), summary.name());
    }
}
