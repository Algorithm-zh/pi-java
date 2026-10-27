package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.piagent.domain.model.ModelCapabilities;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 模型能力 JSON 编解码。
 *
 * <p>缺失字段按 false 处理，便于后续只加字段的演进；未知字段忽略。
 * 非对象或非法 JSON 统一报领域错误，避免半配置进入运行时。</p>
 */
public final class ModelCapabilitiesCodec {

    private final ObjectMapper mapper;

    /**
     * 创建编解码器。
     *
     * @param mapper Jackson 映射器
     */
    public ModelCapabilitiesCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 编码能力。
     *
     * @param capabilities 能力
     * @return JSON
     */
    public String encode(ModelCapabilities capabilities) {
        ObjectNode node = mapper.createObjectNode();
        node.put("streaming", capabilities.streaming());
        node.put("toolCalls", capabilities.toolCalls());
        node.put("thinking", capabilities.thinking());
        node.put("vision", capabilities.vision());
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception error) {
            throw new IllegalStateException("模型能力序列化失败", error);
        }
    }

    /**
     * 解码能力。
     *
     * @param json JSON
     * @return 能力
     */
    public ModelCapabilities decode(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || !node.isObject()) {
                throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "capabilities_json 必须是对象");
            }
            return new ModelCapabilities(
                    node.path("streaming").asBoolean(false),
                    node.path("toolCalls").asBoolean(false),
                    node.path("thinking").asBoolean(false),
                    node.path("vision").asBoolean(false));
        } catch (DomainException error) {
            throw error;
        } catch (Exception error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "capabilities_json 解析失败", error);
        }
    }
}
