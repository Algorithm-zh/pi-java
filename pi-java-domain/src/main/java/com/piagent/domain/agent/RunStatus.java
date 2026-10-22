package com.piagent.domain.agent;

/**
 * 运行状态。
 *
 * <p>v0.1 的 Run 只有四种状态：运行中与三种终态。审批等待（M6）与压缩（M7）
 * 期间 Run 仍保持 {@link #RUNNING}，只通过事件向 UI 暴露子阶段，避免状态机膨胀。</p>
 */
public enum RunStatus {

    /** 正在执行。 */
    RUNNING,

    /** 正常结束，包含 STOP、LENGTH 与 MAX_STEPS。 */
    COMPLETED,

    /** 因模型、传输或持久化错误结束。 */
    FAILED,

    /** 被用户取消或超时。 */
    ABORTED;

    /**
     * 是否为终态。
     *
     * @return true 表示不会再变化
     */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
