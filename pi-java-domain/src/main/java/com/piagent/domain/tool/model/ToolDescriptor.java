package com.piagent.domain.tool.model;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Duration;
import java.util.Objects;

/**
 * 工具目录项。
 *
 * <p>它是不可变定义，没有生命周期；启停由策略表与插件注册表控制。
 * {@code inputSchemaJson} 直接作为 LLM 的工具参数 schema，必须是 JSON 对象文本，
 * 解析责任在基础设施层，领域层只校验存在性与长度。</p>
 *
 * @param name 工具名
 * @param description 给模型看的能力描述
 * @param inputSchemaJson 参数 JSON Schema
 * @param risk 风险级别
 * @param executionMode 执行模式
 * @param timeout 单次执行超时
 */
public record ToolDescriptor(ToolName name, String description, String inputSchemaJson,
        ToolRisk risk, ExecutionMode executionMode, Duration timeout) {

    private static final int MAX_DESCRIPTION_LENGTH = 1_024;
    private static final int MAX_SCHEMA_LENGTH = 20_000;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /**
     * 校验工具定义。
     *
     * @param name 工具名
     * @param description 描述
     * @param inputSchemaJson 参数 schema
     * @param risk 风险
     * @param executionMode 执行模式
     * @param timeout 超时
     */
    public ToolDescriptor {
        Objects.requireNonNull(name, "工具名不能为空");
        description = Guard.notBlank(description, DomainErrorCode.INVALID_ARGUMENT,
                "工具描述不能为空");
        Guard.maxLength(description, MAX_DESCRIPTION_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "工具描述不能超过 " + MAX_DESCRIPTION_LENGTH + " 个字符");
        inputSchemaJson = Guard.notBlank(inputSchemaJson, DomainErrorCode.INVALID_ARGUMENT,
                "工具参数 schema 不能为空");
        Guard.maxLength(inputSchemaJson, MAX_SCHEMA_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                "工具参数 schema 不能超过 " + MAX_SCHEMA_LENGTH + " 个字符");
        Objects.requireNonNull(risk, "risk 不能为空");
        Objects.requireNonNull(executionMode, "executionMode 不能为空");
        Objects.requireNonNull(timeout, "timeout 不能为空");
        Guard.require(!timeout.isZero() && !timeout.isNegative(), DomainErrorCode.INVALID_ARGUMENT,
                "工具超时必须为正数");
        Guard.require(timeout.compareTo(MAX_TIMEOUT) <= 0, DomainErrorCode.INVALID_ARGUMENT,
                "工具超时不能超过 10 分钟");
    }
}
