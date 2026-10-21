package com.piagent.domain.model.llm;

import com.piagent.domain.model.ModelProfile;
import com.piagent.domain.session.model.ThinkingLevel;
import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Objects;

/**
 * 一次流式模型请求。
 *
 * <p>消息复用会话上下文的 {@link AgentMessage}，避免维护两套消息层次；适配器负责
 * 把它们翻译为 provider 协议。工具调用能力与思考级别由调用方先用
 * {@code ModelSelectionPolicy} 校验。</p>
 *
 * @param profile 模型档案（快照）
 * @param systemPrompt 系统提示词，可为空字符串
 * @param messages 会话消息，按时间顺序
 * @param tools 可用工具
 * @param thinkingLevel 思考级别
 */
public record LlmRequest(
        ModelProfile profile,
        String systemPrompt,
        List<AgentMessage> messages,
        List<LlmTool> tools,
        ThinkingLevel thinkingLevel) {

    /**
     * 校验并冻结请求。
     *
     * @param profile 模型档案
     * @param systemPrompt 系统提示词
     * @param messages 消息
     * @param tools 工具
     * @param thinkingLevel 思考级别
     */
    public LlmRequest {
        Objects.requireNonNull(profile, "profile 不能为空");
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        Objects.requireNonNull(messages, "messages 不能为空");
        Objects.requireNonNull(tools, "tools 不能为空");
        Objects.requireNonNull(thinkingLevel, "thinkingLevel 不能为空");
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        long distinctNames = tools.stream().map(LlmTool::name).distinct().count();
        Guard.require(distinctNames == tools.size(), DomainErrorCode.INVALID_ARGUMENT,
                "工具名不能重复");
    }
}
