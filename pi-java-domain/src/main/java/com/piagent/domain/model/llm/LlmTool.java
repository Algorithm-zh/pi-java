package com.piagent.domain.model.llm;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 发给模型的工具定义。
 *
 * <p>只包含模型需要的元数据；真正执行由工具上下文负责。{@code inputSchemaJson}
 * 必须是 JSON Schema 字符串，适配器按 provider 协议转换。</p>
 *
 * @param name 工具名
 * @param description 描述
 * @param inputSchemaJson JSON Schema
 */
public record LlmTool(String name, String description, String inputSchemaJson) {

    /**
     * 校验工具定义。
     *
     * @param name 工具名
     * @param description 描述
     * @param inputSchemaJson JSON Schema
     */
    public LlmTool {
        name = Guard.notBlank(name, DomainErrorCode.INVALID_ARGUMENT, "工具名不能为空");
        description = description == null ? "" : description;
        inputSchemaJson = Guard.notBlank(inputSchemaJson, DomainErrorCode.INVALID_ARGUMENT,
                "工具 JSON Schema 不能为空");
    }
}
