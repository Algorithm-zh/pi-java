package com.piagent.trigger.http.auth;

import com.piagent.application.identity.readmodel.TenantSummary;
import java.time.Instant;

/**
 * 空间响应。
 *
 * @param id 空间标识
 * @param slug 短名
 * @param name 名称
 * @param kind 空间类型：DEFAULT、TEAM 或 PERSONAL
 * @param role 当前账号在该空间的角色
 * @param deletedAt 归档时间；null 表示正常
 */
public record TenantResponse(String id, String slug, String name, String kind, String role,
        Instant deletedAt) {

    /**
     * 由读模型转换。
     *
     * @param summary 空间摘要
     * @return 响应
     */
    public static TenantResponse from(TenantSummary summary) {
        return new TenantResponse(summary.id(), summary.slug(), summary.name(), summary.kind(),
                summary.role(), summary.deletedAt());
    }
}
