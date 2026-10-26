package com.piagent.domain.agent;

/**
 * Run 事件类型。
 *
 * <p>{@code wireName} 是 SSE 与 CLI 共用的稳定契约，使用点分隔的小写名称；
 * 枚举名只服务于代码。新增事件只能追加，不能复用旧名称表达新语义。</p>
 */
public enum RunEventType {

    /** run 开始。 */
    RUN_STARTED("run.started"),

    /** 一轮模型请求开始。 */
    TURN_STARTED("turn.started"),

    /** 助手消息开始。 */
    MESSAGE_STARTED("message.started"),

    /** 助手文本或思考增量。 */
    MESSAGE_DELTA("message.delta"),

    /** 助手消息完成并已落库。 */
    MESSAGE_COMPLETED("message.completed"),

    /** 工具开始执行。 */
    TOOL_STARTED("tool.started"),

    /** 工具执行完成。 */
    TOOL_COMPLETED("tool.completed"),

    /** 工具调用进入待审批。 */
    APPROVAL_REQUESTED("approval.requested"),

    /** 审批进入终态。 */
    APPROVAL_RESOLVED("approval.resolved"),

    /** 上下文压缩开始（自动或 overflow 重试）。 */
    COMPACTION_STARTED("compaction.started"),

    /** 上下文压缩完成并已写入摘要 entry。 */
    COMPACTION_COMPLETED("compaction.completed"),

    /** 一轮工具执行后工作区发生了变化，并已写入改动 entry。 */
    WORKSPACE_CHANGED("workspace.changed"),

    /** 模型更新了待办计划。 */
    PLAN_UPDATED("plan.updated"),

    /** 自动验证命令执行完成。 */
    VERIFICATION_COMPLETED("verification.completed"),

    /** run 正常结束（含取消与超时）。 */
    RUN_COMPLETED("run.completed"),

    /** run 失败。 */
    RUN_FAILED("run.failed"),

    /** SSE 心跳，不进入重放缓冲。 */
    HEARTBEAT("heartbeat"),

    /** 请求的重放起点已被缓冲淘汰。 */
    REPLAY_GAP("replay.gap");

    private final String wireName;

    RunEventType(String wireName) {
        this.wireName = wireName;
    }

    /**
     * SSE/CLI 使用的稳定事件名。
     *
     * @return 点分隔事件名
     */
    public String wireName() {
        return wireName;
    }

    /**
     * 是否为终态事件。
     *
     * @return true 表示 run 已结束
     */
    public boolean isTerminal() {
        return this == RUN_COMPLETED || this == RUN_FAILED;
    }
}
