package com.piagent.trigger.http.governance;

import com.piagent.domain.governance.model.ApprovalDecision;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 审批决策请求。
 *
 * <p>只接受 {@code APPROVE} 与 {@code DENY}；过期、取消等系统终态不能通过
 * 该接口写入。意见可选，去空白后最多 1024 个字符。</p>
 *
 * @param decision 决策：APPROVE / DENY
 * @param reason 决策意见，可为 null
 */
public record DecideApprovalRequest(String decision, String reason) {

    /**
     * 归一化意见。
     *
     * @param decision 决策文本
     * @param reason 意见
     */
    public DecideApprovalRequest {
        reason = normalize(reason);
    }

    /**
     * 转换为领域决策。
     *
     * @return 领域决策
     */
    public ApprovalDecision toDecision() {
        return EnumParse.parse(ApprovalDecision.class, decision, "decision");
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return Guard.maxLength(value.trim(), ApprovalRequest.MAX_REASON_LENGTH,
                DomainErrorCode.INVALID_ARGUMENT,
                "审批意见不能超过 " + ApprovalRequest.MAX_REASON_LENGTH + " 个字符");
    }
}
