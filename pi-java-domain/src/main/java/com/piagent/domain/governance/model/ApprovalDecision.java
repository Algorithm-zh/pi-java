package com.piagent.domain.governance.model;

/**
 * 用户可提交的审批决策。
 *
 * <p>只包含“批准/拒绝”两种主动决策；过期与取消属于系统事件，不能从 HTTP
 * 决策接口写入。领域层用该枚举把外部输入收敛为合法迁移。</p>
 */
public enum ApprovalDecision {

    /** 批准执行。 */
    APPROVE,

    /** 拒绝执行。 */
    DENY;

    /**
     * 决策对应的目标状态。
     *
     * @return APPROVED 或 DENIED
     */
    public ApprovalStatus targetStatus() {
        return this == APPROVE ? ApprovalStatus.APPROVED : ApprovalStatus.DENIED;
    }
}
