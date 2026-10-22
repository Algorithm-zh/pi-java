package com.piagent.infrastructure.persistence.codec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审批脱敏参数的 JSON 编解码。
 *
 * <p>只用于展示与审计，不参与工具执行；因此解码失败不应静默降级成空参数，
 * 否则用户会看到“没有任何参数”的错误审批详情。空参数统一编码为 {@code {}}。</p>
 */
public final class ApprovalArgumentsCodec {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    /**
     * 创建编解码器。
     *
     * @param mapper Jackson 映射器
     */
    public ApprovalArgumentsCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 编码脱敏参数。
     *
     * @param arguments 参数
     * @return JSON 对象
     */
    public String encode(Map<String, Object> arguments) {
        try {
            return mapper.writeValueAsString(arguments == null ? Map.of() : arguments);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("审批参数序列化失败", error);
        }
    }

    /**
     * 解码脱敏参数。
     *
     * @param json JSON
     * @return 参数
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
                        "args_redacted_json 不能包含 null 值");
            }
            return new LinkedHashMap<>(value);
        } catch (DomainException error) {
            throw error;
        } catch (JsonProcessingException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "args_redacted_json 解析失败", error);
        }
    }
}
