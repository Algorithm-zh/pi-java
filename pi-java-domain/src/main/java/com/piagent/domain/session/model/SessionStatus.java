package com.piagent.domain.session.model;

/**
 * 会话状态。
 *
 * <p>压缩属于 Run 状态，不进入会话状态；会话在压缩期间仍为 {@link #RUNNING}。
 */
public enum SessionStatus {

    /** 空闲，没有活跃 run。 */
    IDLE,

    /** 有 run 正在执行。 */
    RUNNING,

    /** run 正在等待人工审批。 */
    WAITING_APPROVAL,

    /** 已归档，只读。 */
    ARCHIVED
}
