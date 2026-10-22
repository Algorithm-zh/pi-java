package com.piagent.domain.agent;

import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次 Agent Run 的不可变状态快照。
 *
 * <p>Run 只存在于进程内，是运行态而不是事实源：所有对用户有意义的产物
 * （消息、工具结果、运行摘要）都会写入会话 entry。这样做避免为运行态建表，
 * 也避免进程崩溃后出现“数据库里有一条永远 RUNNING 的 run”。
 *
 * <p>状态迁移：
 * <ul>
 *   <li>{@code RUNNING} 只能通过 {@link #recordTurn}、{@link #recordToolCalls}
 *       累积计数与用量；</li>
 *   <li>只有 {@link #finish} 能进入终态，且终态不可再次变更；</li>
 *   <li>终态由终止原因唯一决定：CANCELLED/TIMEOUT → ABORTED，
 *       ERROR → FAILED，其余 → COMPLETED。</li>
 * </ul>
 *
 * @param id 运行标识
 * @param sessionId 所属会话
 * @param status 当前状态
 * @param budget 冻结的执行预算
 * @param turns 已完成的模型请求数
 * @param toolCalls 已执行的工具调用数
 * @param usage 累计 token 用量
 * @param startedAt 开始时间
 * @param finishedAt 结束时间；运行中为 null
 * @param stopReason 终止原因；运行中为 null
 * @param errorMessage 错误消息；仅失败时可能非空
 */
public record AgentRun(
        RunId id,
        SessionId sessionId,
        RunStatus status,
        RunBudget budget,
        int turns,
        int toolCalls,
        TokenUsage usage,
        Instant startedAt,
        Instant finishedAt,
        RunStopReason stopReason,
        String errorMessage) {

    private static final int MAX_ERROR_LENGTH = 4_000;

    /**
     * 校验运行状态快照。
     *
     * @param id 运行标识
     * @param sessionId 会话标识
     * @param status 状态
     * @param budget 预算
     * @param turns 轮数
     * @param toolCalls 工具调用数
     * @param usage 用量
     * @param startedAt 开始时间
     * @param finishedAt 结束时间
     * @param stopReason 终止原因
     * @param errorMessage 错误消息
     */
    public AgentRun {
        Objects.requireNonNull(id, "runId 不能为空");
        Objects.requireNonNull(sessionId, "sessionId 不能为空");
        Objects.requireNonNull(status, "status 不能为空");
        Objects.requireNonNull(budget, "budget 不能为空");
        Objects.requireNonNull(usage, "usage 不能为空");
        Objects.requireNonNull(startedAt, "startedAt 不能为空");
        Guard.require(turns >= 0, DomainErrorCode.INVALID_ARGUMENT, "turns 不能为负数");
        Guard.require(toolCalls >= 0, DomainErrorCode.INVALID_ARGUMENT, "toolCalls 不能为负数");
        if (stopReason != null && stopReason == RunStopReason.ERROR) {
            errorMessage = Guard.notBlank(errorMessage, DomainErrorCode.INVALID_ARGUMENT,
                    "失败 run 必须提供 errorMessage");
        }
        if (errorMessage != null) {
            Guard.maxLength(errorMessage, MAX_ERROR_LENGTH, DomainErrorCode.INVALID_ARGUMENT,
                    "errorMessage 不能超过 " + MAX_ERROR_LENGTH + " 个字符");
        }
        if (status.isTerminal()) {
            Objects.requireNonNull(finishedAt, "终态 run 必须有 finishedAt");
            Objects.requireNonNull(stopReason, "终态 run 必须有 stopReason");
        } else {
            Guard.require(finishedAt == null, DomainErrorCode.INVALID_ARGUMENT,
                    "运行中的 run 不能有 finishedAt");
            Guard.require(stopReason == null, DomainErrorCode.INVALID_ARGUMENT,
                    "运行中的 run 不能有 stopReason");
        }
    }

    /**
     * 创建运行中的 run。
     *
     * @param id 运行标识
     * @param sessionId 会话标识
     * @param budget 预算
     * @param startedAt 开始时间
     * @return 初始快照
     */
    public static AgentRun start(RunId id, SessionId sessionId, RunBudget budget, Instant startedAt) {
        return new AgentRun(id, sessionId, RunStatus.RUNNING, budget, 0, 0,
                TokenUsage.zero(), startedAt, null, null, null);
    }

    /**
     * 记录一次已完成的模型请求。
     *
     * @param turnUsage 本轮用量
     * @return 新快照
     */
    public AgentRun recordTurn(TokenUsage turnUsage) {
        requireRunning();
        Objects.requireNonNull(turnUsage, "turnUsage 不能为空");
        return new AgentRun(id, sessionId, status, budget, turns + 1, toolCalls,
                addUsage(usage, turnUsage), startedAt, null, null, null);
    }

    /**
     * 记录一批已执行的工具调用。
     *
     * @param executed 本批次数
     * @return 新快照
     */
    public AgentRun recordToolCalls(int executed) {
        requireRunning();
        Guard.require(executed >= 0, DomainErrorCode.INVALID_ARGUMENT, "executed 不能为负数");
        return new AgentRun(id, sessionId, status, budget, turns, toolCalls + executed,
                usage, startedAt, null, null, null);
    }

    /**
     * 结束 run。
     *
     * @param reason 终止原因
     * @param error 错误消息；非 ERROR 时可为 null
     * @param finishedAt 结束时间
     * @return 终态快照
     */
    public AgentRun finish(RunStopReason reason, String error, Instant finishedAt) {
        requireRunning();
        Objects.requireNonNull(reason, "stopReason 不能为空");
        Objects.requireNonNull(finishedAt, "finishedAt 不能为空");
        return new AgentRun(id, sessionId, statusOf(reason), budget, turns, toolCalls,
                usage, startedAt, finishedAt, reason, error);
    }

    /**
     * 是否已经结束。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * 运行时长；运行中按给定当前时间计算。
     *
     * @param now 当前时间
     * @return 毫秒数
     */
    public long durationMillis(Instant now) {
        Instant end = finishedAt == null ? Objects.requireNonNull(now, "now 不能为空") : finishedAt;
        return Math.max(0, end.toEpochMilli() - startedAt.toEpochMilli());
    }

    private void requireRunning() {
        Guard.require(status == RunStatus.RUNNING, DomainErrorCode.INVALID_STATE_TRANSITION,
                "run 已结束，不能继续变更: " + id);
    }

    private static RunStatus statusOf(RunStopReason reason) {
        return switch (reason) {
            case CANCELLED, TIMEOUT -> RunStatus.ABORTED;
            case ERROR -> RunStatus.FAILED;
            case STOP, LENGTH, MAX_STEPS -> RunStatus.COMPLETED;
        };
    }

    private static TokenUsage addUsage(TokenUsage current, TokenUsage delta) {
        return new TokenUsage(
                Math.addExact(current.input(), delta.input()),
                Math.addExact(current.output(), delta.output()),
                Math.addExact(current.cacheRead(), delta.cacheRead()),
                Math.addExact(current.cacheWrite(), delta.cacheWrite()),
                Math.addExact(current.totalTokens(), delta.totalTokens()));
    }
}
