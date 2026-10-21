package com.piagent.domain.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 真正发送给 provider 的模型标识。
 *
 * @param provider 协议类型
 * @param modelName provider 侧的模型名，如 {@code deepseek-chat}
 */
public record ModelRef(ProviderType provider, String modelName) {

    /**
     * 校验模型引用。
     *
     * @param provider 协议类型
     * @param modelName 模型名
     */
    public ModelRef {
        Objects.requireNonNull(provider, "provider 不能为空");
        modelName = Guard.notBlank(modelName, DomainErrorCode.INVALID_ARGUMENT, "modelName 不能为空");
        Guard.maxLength(modelName, 128, DomainErrorCode.INVALID_ARGUMENT, "modelName 不能超过 128 个字符");
    }
}
