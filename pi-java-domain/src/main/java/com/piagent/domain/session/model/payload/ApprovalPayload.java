package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 审批决策审计载荷。
 *
 * <p>决策字符串由治理上下文定义（APPROVED/DENIED/EXPIRED/CANCELLED），
 * 会话上下文只负责记录，不解释状态机。
 *
 * @param approvalId 审批 ID
 * @param toolCallId 工具调用 ID
 * @param toolName 工具名
 * @param decision 决策
 * @param reason 审批意见，可为 null
 * @param decidedBy 决策者
 */
public record ApprovalPayload(
        String approvalId,
        String toolCallId,
        String toolName,
        String decision,
        String reason,
        String decidedBy) implements EntryPayload {

    /**
     * 校验审批载荷。
     *
     * @param approvalId 审批 ID
     * @param toolCallId 调用 ID
     * @param toolName 工具名
     * @param decision 决策
     * @param reason 意见
     * @param decidedBy 决策者
     */
    public ApprovalPayload {
        Guard.notBlank(approvalId, DomainErrorCode.INVALID_ARGUMENT, "approvalId 不能为空");
        Guard.notBlank(toolCallId, DomainErrorCode.INVALID_ARGUMENT, "toolCallId 不能为空");
        Guard.notBlank(toolName, DomainErrorCode.INVALID_ARGUMENT, "toolName 不能为空");
        Guard.notBlank(decision, DomainErrorCode.INVALID_ARGUMENT, "decision 不能为空");
        Guard.notBlank(decidedBy, DomainErrorCode.INVALID_ARGUMENT, "decidedBy 不能为空");
    }

    @Override
    public EntryType entryType() {
        return EntryType.APPROVAL;
    }
}
