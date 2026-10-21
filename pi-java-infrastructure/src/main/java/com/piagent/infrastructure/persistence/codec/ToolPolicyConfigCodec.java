package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具策略扩展配置的 JSON 编解码。
 *
 * <p>配置必须是 JSON 对象；值为 {@code null} 会被拒绝，因为领域值对象
 * 使用 {@code Map.copyOf} 冻结，不接受 null 值。空配置统一编码为 {@code {}}。</p>
 */
public final class ToolPolicyConfigCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    /**
     * 创建编解码器。
     *
     * @param mapper Jackson 映射器
     */
    public ToolPolicyConfigCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 编码配置。
     *
     * @param config 配置
     * @return JSON 对象
     */
    public String encode(Map<String, Object> config) {
        Map<String, Object> value = config == null ? Map.of() : config;
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("工具策略配置序列化失败", error);
        }
    }

    /**
     * 解码配置。
     *
     * @param json JSON
     * @return 配置
     */
    public Map<String, Object> decode(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> value = mapper.readValue(json, MAP_TYPE);
            if (value == null) {
                return Map.of();
            }
            if (value.values().stream().anyMatch(item -> item == null)) {
                throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                        "config_json 不能包含 null 值");
            }
            return new LinkedHashMap<>(value);
        } catch (DomainException error) {
            throw error;
        } catch (JsonProcessingException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "config_json 解析失败", error);
        }
    }
}
