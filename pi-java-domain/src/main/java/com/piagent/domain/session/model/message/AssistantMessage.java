package com.piagent.domain.session.model.message;

import com.piagent.domain.session.model.TokenUsage;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 助手消息。
 *
 * @param content 文本、思考或工具调用块
 * @param provider 模型提供方
 * @param model 模型标识
 * @param usage token 用量
 * @param stopReason 本次响应的停止原因
 * @param errorMessage 错误信息，可为 null
 * @param timestamp 创建时间
 */
public record AssistantMessage(
        List<ContentBlock> content,
        String provider,
        String model,
        TokenUsage usage,
        AssistantStopReason stopReason,
        String errorMessage,
        Instant timestamp) implements AgentMessage {

    /**
     * 校验助手消息。
     *
     * @param content 内容块
     * @param provider 提供方
     * @param model 模型
     * @param usage 用量
     * @param stopReason 停止原因
     * @param errorMessage 错误信息
     * @param timestamp 时间
     */
    public AssistantMessage {
        Objects.requireNonNull(content, "content 不能为空");
        Guard.notBlank(provider, DomainErrorCode.INVALID_ARGUMENT, "provider 不能为空");
        Guard.notBlank(model, DomainErrorCode.INVALID_ARGUMENT, "model 不能为空");
        Objects.requireNonNull(usage, "usage 不能为空");
        Objects.requireNonNull(stopReason, "stopReason 不能为空");
        Objects.requireNonNull(timestamp, "timestamp 不能为空");
        content = List.copyOf(content);
    }
}
