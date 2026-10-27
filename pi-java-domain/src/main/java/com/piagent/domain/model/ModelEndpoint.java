package com.piagent.domain.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 模型接入端点。
 *
 * <p>只保存环境变量名，不保存 API Key 明文。{@link ProviderType#FAKE} 允许没有
 * URL 与密钥，用于离线演示与测试；其他 provider 必须提供 http(s) 地址和合法的
 * 环境变量名。</p>
 *
 * @param provider 提供方类型
 * @param baseUrl API 根地址，末尾斜杠会被去掉；FAKE 可为空字符串
 * @param apiKeyEnv 密钥环境变量名；FAKE 可为空字符串
 * @param modelName 发给 provider 的模型名
 * @param contextWindow 上下文窗口 token 数
 * @param maxOutputTokens 单次响应最大输出 token 数
 * @param capabilities 能力声明
 */
public record ModelEndpoint(
        ProviderType provider,
        String baseUrl,
        String apiKeyEnv,
        String modelName,
        int contextWindow,
        int maxOutputTokens,
        ModelCapabilities capabilities) {

    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final int MAX_MODEL_NAME_LENGTH = 128;
    private static final int MAX_BASE_URL_LENGTH = 512;

    /**
     * 校验端点配置并规范化 URL 与环境变量名。
     *
     * @param provider 提供方
     * @param baseUrl API 根地址
     * @param apiKeyEnv 密钥环境变量名
     * @param modelName 模型名
     * @param contextWindow 上下文窗口
     * @param maxOutputTokens 最大输出
     * @param capabilities 能力
     */
    public ModelEndpoint {
        Objects.requireNonNull(provider, "provider 不能为空");
        modelName = Guard.notBlank(modelName, DomainErrorCode.INVALID_ARGUMENT, "modelName 不能为空");
        Guard.maxLength(modelName, MAX_MODEL_NAME_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "modelName 不能超过 " + MAX_MODEL_NAME_LENGTH + " 个字符");
        Guard.require(contextWindow > 0, DomainErrorCode.INVALID_ARGUMENT, "contextWindow 必须大于 0");
        Guard.require(maxOutputTokens > 0, DomainErrorCode.INVALID_ARGUMENT, "maxOutputTokens 必须大于 0");
        Guard.require(maxOutputTokens <= contextWindow, DomainErrorCode.INVALID_ARGUMENT,
                "maxOutputTokens 不能超过 contextWindow");
        Objects.requireNonNull(capabilities, "capabilities 不能为空");
        baseUrl = normalizeBaseUrl(provider, baseUrl);
        apiKeyEnv = normalizeApiKeyEnv(provider, apiKeyEnv);
    }

    /**
     * 创建离线 Fake 端点。
     *
     * @param modelName 模型名
     * @return Fake 端点
     */
    public static ModelEndpoint fake(String modelName) {
        return new ModelEndpoint(ProviderType.FAKE, "", "", modelName, 128_000, 8_192,
                ModelCapabilities.demo());
    }

    /**
     * 创建 OpenAI-compatible 端点。
     *
     * @param baseUrl API 根地址
     * @param apiKeyEnv 密钥环境变量名
     * @param modelName 模型名
     * @param contextWindow 上下文窗口
     * @param maxOutputTokens 最大输出
     * @return OpenAI-compatible 端点
     */
    public static ModelEndpoint openAiCompatible(String baseUrl, String apiKeyEnv, String modelName,
            int contextWindow, int maxOutputTokens) {
        return new ModelEndpoint(ProviderType.OPENAI_COMPATIBLE, baseUrl, apiKeyEnv, modelName,
                contextWindow, maxOutputTokens, ModelCapabilities.defaults());
    }

    /**
     * 返回逻辑模型引用。
     *
     * @return provider + modelName
     */
    public ModelRef ref() {
        return new ModelRef(provider, modelName);
    }

    /**
     * 是否需要密钥。
     *
     * @return 非 Fake provider 返回 true
     */
    public boolean requiresApiKey() {
        return provider != ProviderType.FAKE;
    }

    private static String normalizeBaseUrl(ProviderType provider, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            Guard.require(provider == ProviderType.FAKE, DomainErrorCode.INVALID_ARGUMENT,
                    provider + " provider 必须配置 baseUrl");
            return "";
        }
        Guard.maxLength(value, MAX_BASE_URL_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "baseUrl 不能超过 " + MAX_BASE_URL_LENGTH + " 个字符");
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException ex) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "baseUrl 不是合法 URI: " + value, ex);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        Guard.require((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null,
                DomainErrorCode.INVALID_ARGUMENT, "baseUrl 必须是带主机名的 http(s) 地址: " + value);
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String normalizeApiKeyEnv(ProviderType provider, String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            Guard.require(provider == ProviderType.FAKE, DomainErrorCode.INVALID_ARGUMENT,
                    provider + " provider 必须配置 apiKeyEnv（只存环境变量名）");
            return "";
        }
        Guard.require(ENV_NAME.matcher(value).matches(), DomainErrorCode.INVALID_ARGUMENT,
                "apiKeyEnv 只能包含大写字母、数字与下划线，且不能以数字开头: " + value);
        return value;
    }
}
