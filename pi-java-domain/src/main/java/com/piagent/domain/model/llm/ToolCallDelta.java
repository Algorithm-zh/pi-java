package com.piagent.domain.model.llm;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 工具调用参数增量。
 *
 * <p>OpenAI-compatible 协议会把一个工具调用拆成多个 chunk；{@code index} 用于
 * 归并同一调用，{@code toolCallId} 与 {@code toolName} 通常只在首个 chunk 出现，
 * 后续 chunk 为空字符串。完整参数以 {@link LlmResponse} 中的工具调用块为准。</p>
 *
 * @param index 本次响应内的调用序号
 * @param toolCallId provider 调用 ID，后续片段可为空
 * @param toolName 工具名，后续片段可为空
 * @param argumentsFragment 参数 JSON 片段
 */
public record ToolCallDelta(int index, String toolCallId, String toolName, String argumentsFragment)
        implements LlmDelta {

    /**
     * 校验工具调用增量。
     *
     * @param index 调用序号
     * @param toolCallId 调用 ID
     * @param toolName 工具名
     * @param argumentsFragment 参数片段
     */
    public ToolCallDelta {
        Guard.require(index >= 0, DomainErrorCode.INVALID_ARGUMENT, "工具调用 index 不能为负数");
        toolCallId = toolCallId == null ? "" : toolCallId;
        toolName = toolName == null ? "" : toolName;
        Objects.requireNonNull(argumentsFragment, "argumentsFragment 不能为空");
    }
}
