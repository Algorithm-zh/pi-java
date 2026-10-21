package com.piagent.domain.session.model.payload;

import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.session.model.EntryType;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 一次 run 的结束摘要载荷。
 *
 * @param runId run 标识
 * @param stopReason 终止原因（字符串形式，避免会话上下文依赖运行时枚举）
 * @param usage token 用量
 * @param turns 模型请求轮数
 * @param toolCalls 工具调用次数
 * @param durationMs 总耗时毫秒
 * @param errorMessage 错误信息，可为 null
 */
public record RunSummaryPayload(
        String runId,
        String stopReason,
        TokenUsage usage,
        int turns,
        int toolCalls,
        long durationMs,
        String errorMessage) implements EntryPayload {

    /**
     * 校验 run 摘要。
     *
     * @param runId run ID
     * @param stopReason 终止原因
     * @param usage 用量
     * @param turns 轮数
     * @param toolCalls 工具调用数
     * @param durationMs 耗时
     * @param errorMessage 错误
     */
    public RunSummaryPayload {
        Guard.notBlank(runId, DomainErrorCode.INVALID_ARGUMENT, "runId 不能为空");
        Guard.notBlank(stopReason, DomainErrorCode.INVALID_ARGUMENT, "stopReason 不能为空");
        Objects.requireNonNull(usage, "usage 不能为空");
        Guard.require(turns >= 0, DomainErrorCode.INVALID_ARGUMENT, "turns 不能为负数");
        Guard.require(toolCalls >= 0, DomainErrorCode.INVALID_ARGUMENT, "toolCalls 不能为负数");
        Guard.require(durationMs >= 0, DomainErrorCode.INVALID_ARGUMENT, "durationMs 不能为负数");
    }

    @Override
    public EntryType entryType() {
        return EntryType.RUN_SUMMARY;
    }
}
