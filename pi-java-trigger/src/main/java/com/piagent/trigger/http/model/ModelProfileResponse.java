package com.piagent.trigger.http.model;

import com.piagent.domain.model.ModelProfile;

/**
 * 模型档案响应。
 *
 * @param id 档案标识
 * @param displayName 展示名
 * @param endpoint 端点（仅含密钥环境变量名）
 * @param enabled 是否启用
 * @param apiKeyConfigured 密钥环境变量是否已配置；Fake 固定为 true
 * @param version 乐观锁版本
 */
public record ModelProfileResponse(
        String id,
        String displayName,
        ModelEndpointDto endpoint,
        boolean enabled,
        boolean apiKeyConfigured,
        long version) {

    /**
     * 从领域聚合转换。
     *
     * @param profile 模型档案
     * @param apiKeyConfigured 密钥环境变量是否已配置
     * @return 响应
     */
    public static ModelProfileResponse from(ModelProfile profile, boolean apiKeyConfigured) {
        return new ModelProfileResponse(profile.id().value(), profile.displayName(),
                ModelEndpointDto.from(profile.endpoint()), profile.enabled(),
                apiKeyConfigured, profile.version());
    }
}
