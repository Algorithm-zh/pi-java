package com.piagent.application.model.command;

import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 更新模型档案命令。
 *
 * <p>只更新展示名与端点；启停通过独立用例完成，避免一次请求混合状态变更。</p>
 *
 * @param modelId 档案标识
 * @param displayName 新展示名
 * @param endpoint 新端点
 */
public record UpdateModelProfileCommand(String modelId, String displayName, ModelEndpoint endpoint) {

    /**
     * 校验命令。
     *
     * @param modelId 档案标识
     * @param displayName 展示名
     * @param endpoint 端点
     */
    public UpdateModelProfileCommand {
        modelId = Guard.notBlank(modelId, DomainErrorCode.INVALID_ARGUMENT, "modelId 不能为空");
        displayName = Guard.notBlank(displayName, DomainErrorCode.INVALID_ARGUMENT, "displayName 不能为空");
        Objects.requireNonNull(endpoint, "endpoint 不能为空");
    }
}
