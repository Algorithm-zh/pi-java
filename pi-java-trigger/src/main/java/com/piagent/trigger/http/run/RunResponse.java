package com.piagent.trigger.http.run;

import com.piagent.domain.agent.AgentRun;
import com.piagent.trigger.http.session.UsageResponse;

/**
 * 运行状态响应。
 *
 * @param runId 运行 ID
 * @param sessionId 会话 ID
 * @param status 状态
 * @param stopReason 终止原因
 * @param turns 已完成轮数
 * @param toolCalls 已执行工具数
 * @param usage 累计用量
 * @param startedAt 开始时间
 * @param finishedAt 结束时间
 * @param durationMs 已运行毫秒数
 * @param errorMessage 错误消息
 */
public record RunResponse(String runId, String sessionId, String status, String stopReason,
        int turns, int toolCalls, UsageResponse usage, String startedAt, String finishedAt,
        long durationMs, String errorMessage) {

    /**
     * 从领域快照转换。
     *
     * @param run 运行快照
     * @return 响应
     */
    public static RunResponse from(AgentRun run) {
        String finishedAt = run.finishedAt() == null ? null : run.finishedAt().toString();
        long duration = run.finishedAt() == null ? 0 : run.durationMillis(run.finishedAt());
        return new RunResponse(run.id().toString(), run.sessionId().toString(),
                run.status().name(), run.stopReason() == null ? null : run.stopReason().name(),
                run.turns(), run.toolCalls(), UsageResponse.from(run.usage()),
                run.startedAt().toString(), finishedAt, duration, run.errorMessage());
    }
}
