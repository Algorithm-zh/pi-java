package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.TenantSummary;
import java.time.Instant;

/**
 * 租户响应。
 *
 * @param id 租户标识
 * @param slug 短名
 * @param name 名称
 * @param deletedAt 归档时间；null 表示正常
 */
public record TenantResponse(String id, String slug, String name, Instant deletedAt) {

    /**
     * 由读模型转换。
     *
     * @param summary 租户摘要
     * @return 响应
     */
    public static TenantResponse from(TenantSummary summary) {
        return new TenantResponse(summary.id(), summary.slug(), summary.name(),
                summary.deletedAt());
    }
}
