package com.piagent.domain.governance.model;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import java.util.Objects;

/**
 * 一次审批所描述的工具调用。
 *
 * <p>把“谁在哪个 run 里要调用什么工具、参数是什么、风险多高”收敛成一个值对象；
 * {@link ApprovalRequest} 只负责状态机，不重复保存这些不可变事实。</p>
 *
 * @param sessionId 会话标识
 * @param runId 运行标识
 * @param toolCallId 工具调用标识
 * @param toolName 工具名
 * @param arguments 脱敏参数与摘要
 * @param riskLevel 工具风险级别
 */
public record ApprovalContext(SessionId sessionId, RunId runId, ToolCallId toolCallId,
        ToolName toolName, ApprovalArguments arguments, ToolRisk riskLevel) {

    /**
     * 校验审批对象。
     *
     * @param sessionId 会话标识
     * @param runId 运行标识
     * @param toolCallId 调用标识
     * @param toolName 工具名
     * @param arguments 参数
     * @param riskLevel 风险
     */
    public ApprovalContext {
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(toolCallId, "toolCallId 不能为空");
        Objects.requireNonNull(toolName, "toolName 不能为空");
        Objects.requireNonNull(arguments, "arguments 不能为空");
        Objects.requireNonNull(riskLevel, "riskLevel 不能为空");
    }
}
