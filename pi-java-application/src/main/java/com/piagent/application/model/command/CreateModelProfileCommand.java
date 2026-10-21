package com.piagent.application.model.command;

import com.piagent.domain.model.ModelEndpoint;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 创建模型档案命令。
 *
 * <p>端点携带 provider、地址、密钥环境变量名与能力；命令本身不含密钥。</p>
 *
 * @param modelId 档案标识
 * @param displayName 展示名
 * @param endpoint 接入端点
 */
public record CreateModelProfileCommand(String modelId, String displayName, ModelEndpoint endpoint) {

    /**
     * 校验命令。
     *
     * @param modelId 档案标识
     * @param displayName 展示名
     * @param endpoint 端点
     */
    public CreateModelProfileCommand {
        modelId = Guard.notBlank(modelId, DomainErrorCode.INVALID_ARGUMENT, "modelId 不能为空");
        displayName = Guard.notBlank(displayName, DomainErrorCode.INVALID_ARGUMENT, "displayName 不能为空");
        Objects.requireNonNull(endpoint, "endpoint 不能为空");
    }
}
