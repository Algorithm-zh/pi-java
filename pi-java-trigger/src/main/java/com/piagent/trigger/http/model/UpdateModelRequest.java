package com.piagent.trigger.http.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 更新模型档案请求。
 *
 * @param displayName 新展示名
 * @param endpoint 新端点
 */
public record UpdateModelRequest(String displayName, ModelEndpointDto endpoint) {

    /**
     * 校验必填端点，缺失时按非法参数处理。
     *
     * @param displayName 展示名
     * @param endpoint 端点
     */
    public UpdateModelRequest {
        if (endpoint == null) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "endpoint 不能为空");
        }
    }
}
