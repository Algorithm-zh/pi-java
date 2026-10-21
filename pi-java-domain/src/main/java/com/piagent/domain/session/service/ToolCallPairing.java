package com.piagent.domain.session.service;

import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.session.model.message.AssistantMessage;
import com.piagent.domain.session.model.message.ContentBlock;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.session.model.message.ToolResultMessage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import com.piagent.domain.shared.Guard;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 工具调用配对校验器。
 *
 * <p>按消息顺序维护“已发出但未返回”的调用集合。除工具结果外的任何消息出现时，
 * 集合必须为空；分支末尾允许悬空调用，由启动恢复流程补写错误结果。
 */
final class ToolCallPairing {

    private final Map<String, ToolCallBlock> pending = new LinkedHashMap<>();
    private final Set<String> seenCallIds = new HashSet<>();

    /**
     * 校验并推进配对状态。
     *
     * @param message 下一条上下文消息
     */
    void accept(AgentMessage message) {
        if (!(message instanceof ToolResultMessage) && !pending.isEmpty()) {
            throw new DomainException(DomainErrorCode.INVALID_ENTRY,
                    "存在未完成的工具调用，不能插入其他消息: " + pending.keySet());
        }
        if (message instanceof AssistantMessage assistant) {
            acceptAssistant(assistant);
        } else if (message instanceof ToolResultMessage result) {
            acceptResult(result);
        }
    }

    private void acceptAssistant(AssistantMessage assistant) {
        for (ContentBlock block : assistant.content()) {
            if (block instanceof ToolCallBlock call) {
                Guard.require(seenCallIds.add(call.id()), DomainErrorCode.TOOL_CALL_MISMATCH,
                        "工具调用 ID 重复: " + call.id());
                pending.put(call.id(), call);
            }
        }
    }

    private void acceptResult(ToolResultMessage result) {
        ToolCallBlock call = pending.remove(result.toolCallId());
        Guard.require(call != null, DomainErrorCode.TOOL_CALL_MISMATCH,
                "工具结果找不到对应的调用: " + result.toolCallId());
        Guard.require(call.name().equals(result.toolName()), DomainErrorCode.TOOL_CALL_MISMATCH,
                "工具结果名称与调用不一致: " + result.toolCallId());
    }
}
