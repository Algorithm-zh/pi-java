package com.piagent.trigger.http.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;

/**
 * 创建模型档案请求。
 *
 * @param id 档案标识
 * @param displayName 展示名
 * @param endpoint 端点
 */
public record CreateModelRequest(String id, String displayName, ModelEndpointDto endpoint) {

    /**
     * 校验必填端点，缺失时按非法参数处理。
     *
     * @param id 档案标识
     * @param displayName 展示名
     * @param endpoint 端点
     */
    public CreateModelRequest {
        if (endpoint == null) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT, "endpoint 不能为空");
        }
    }
}
