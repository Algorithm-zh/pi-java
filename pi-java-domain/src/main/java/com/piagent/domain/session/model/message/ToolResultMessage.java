package com.piagent.domain.session.model.message;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 工具执行结果消息。
 *
 * @param toolCallId 对应的工具调用 ID
 * @param toolName 工具名
 * @param content 结果内容块
 * @param isError 是否为错误结果
 * @param details 结构化细节，只允许 JSON 可序列化值
 * @param timestamp 创建时间
 */
public record ToolResultMessage(
        String toolCallId,
        String toolName,
        List<ContentBlock> content,
        boolean isError,
        Map<String, Object> details,
        Instant timestamp) implements AgentMessage {

    /**
     * 校验工具结果。
     *
     * @param toolCallId 调用 ID
     * @param toolName 工具名
     * @param content 内容块
     * @param isError 是否错误
     * @param details 细节
     * @param timestamp 时间
     */
    public ToolResultMessage {
        Guard.notBlank(toolCallId, DomainErrorCode.INVALID_ARGUMENT, "toolCallId 不能为空");
        Guard.notBlank(toolName, DomainErrorCode.INVALID_ARGUMENT, "toolName 不能为空");
        Objects.requireNonNull(content, "content 不能为空");
        Objects.requireNonNull(details, "details 不能为空");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
        content = List.copyOf(content);
        details = Map.copyOf(details);
    }
}
