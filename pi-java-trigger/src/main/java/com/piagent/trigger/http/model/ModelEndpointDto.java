package com.piagent.trigger.http.model;

import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.model.ProviderType;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.Locale;

/**
 * 模型端点 DTO。
 *
 * <p>只包含密钥环境变量名；API 不接收也不返回密钥明文。</p>
 *
 * @param provider 提供方，取值 FAKE / OPENAI_COMPATIBLE / ANTHROPIC
 * @param baseUrl API 根地址
 * @param apiKeyEnv 密钥环境变量名
 * @param modelName 模型名
 * @param contextWindow 上下文窗口
 * @param maxOutputTokens 最大输出
 * @param capabilities 能力
 */
public record ModelEndpointDto(
        String provider,
        String baseUrl,
        String apiKeyEnv,
        String modelName,
        int contextWindow,
        int maxOutputTokens,
        ModelCapabilitiesDto capabilities) {

    /**
     * 转为领域端点。
     *
     * @return 领域端点
     */
    public ModelEndpoint toDomain() {
        if (capabilities == null) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "capabilities 不能为空");
        }
        return new ModelEndpoint(parseProvider(provider), baseUrl, apiKeyEnv, modelName,
                contextWindow, maxOutputTokens, capabilities.toDomain());
    }

    /**
     * 从领域端点转换。
     *
     * @param endpoint 领域端点
     * @return DTO
     */
    public static ModelEndpointDto from(ModelEndpoint endpoint) {
        return new ModelEndpointDto(endpoint.provider().name(), endpoint.baseUrl(), endpoint.apiKeyEnv(),
                endpoint.modelName(), endpoint.contextWindow(), endpoint.maxOutputTokens(),
                ModelCapabilitiesDto.from(endpoint.capabilities()));
    }

    private static ProviderType parseProvider(String value) {
        if (value == null || value.isBlank()) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "provider 不能为空");
        }
        try {
            return ProviderType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "未知的 provider: " + value);
        }
    }
}
