package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Map;
import java.util.Objects;

/**
 * 扩展状态载荷，不参与 LLM 上下文。
 *
 * @param customType 扩展类型标识
 * @param data 扩展数据，只允许 JSON 可序列化值
 */
public record CustomPayload(String customType, Map<String, Object> data) implements EntryPayload {

    /**
     * 校验扩展状态。
     *
     * @param customType 扩展类型
     * @param data 数据
     */
    public CustomPayload {
        Guard.notBlank(customType, DomainErrorCode.INVALID_ARGUMENT, "customType 不能为空");
        Objects.requireNonNull(data, "data 不能为空");
        data = Map.copyOf(data);
    }

    @Override
    public EntryType entryType() {
        return EntryType.CUSTOM;
    }
}
