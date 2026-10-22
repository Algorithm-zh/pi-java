package com.piagent.domain.governance.model;

/**
 * 审批请求状态。
 *
 * <p>只有 {@link #PENDING} 可以迁移到其余终态；终态之间不可再变。
 * {@code EXPIRED} 与 {@code CANCELLED} 由系统写入，用户决策只能产生
 * {@code APPROVED} 或 {@code DENIED}。</p>
 */
public enum ApprovalStatus {

    /** 等待用户决策。 */
    PENDING,

    /** 用户批准，工具可以执行。 */
    APPROVED,

    /** 用户拒绝，工具不得执行。 */
    DENIED,

    /** 超过有效期仍无决策。 */
    EXPIRED,

    /** run 被取消或服务重启，待办失效。 */
    CANCELLED;

    /**
     * 是否仍在等待决策。
     *
     * @return true 表示 PENDING
     */
    public boolean isPending() {
        return this == PENDING;
    }

    /**
     * 是否已进入终态。
     *
     * @return true 表示不再是 PENDING
     */
    public boolean isTerminal() {
        return this != PENDING;
    }
}
