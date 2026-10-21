package com.piagent.trigger.http.model;

import com.piagent.domain.model.ModelCapabilities;

/**
 * 模型能力 DTO。
 *
 * @param streaming 是否支持流式
 * @param toolCalls 是否支持工具调用
 * @param thinking 是否支持思考
 */
public record ModelCapabilitiesDto(boolean streaming, boolean toolCalls, boolean thinking) {

    /**
     * 转为领域能力。
     *
     * @return 领域能力
     */
    public ModelCapabilities toDomain() {
        return new ModelCapabilities(streaming, toolCalls, thinking);
    }

    /**
     * 从领域能力转换。
     *
     * @param capabilities 领域能力
     * @return DTO
     */
    public static ModelCapabilitiesDto from(ModelCapabilities capabilities) {
        return new ModelCapabilitiesDto(capabilities.streaming(), capabilities.toolCalls(),
                capabilities.thinking());
    }
}
