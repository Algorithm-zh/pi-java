package com.piagent.domain.subagent;

import com.piagent.domain.agent.RunStopReason;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.TokenUsage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.Objects;

/**
 * 一次子代理执行的结果。
 *
 * <p>这是<b>给父模型看</b>的结构化结果，而不是异常：即使子代理失败、超时或被取消，
 * 父模型也必须拿到一条正常的 toolResult，从中得知发生了什么并自行决定下一步。
 * 因此 {@code stopReason} 是结果的一部分，{@code isError()} 由它派生。</p>
 *
 * @param childSessionId 子会话标识
 * @param stopReason 子 run 的终止原因
 * @param summary 子代理产出的文本摘要；失败时为错误说明
 * @param usage 子代理累计用量
 * @param durationMillis 执行耗时
 * @param turns 子代理完成的模型轮数
 * @param toolCalls 子代理执行过的工具调用数
 */
public record SubagentResult(
        SessionId childSessionId,
        RunStopReason stopReason,
        String summary,
        TokenUsage usage,
        long durationMillis,
        int turns,
        int toolCalls) {

    /** 摘要最大长度（回写父会话时会再截断）。 */
    public static final int MAX_SUMMARY_LENGTH = 20_000;

    /**
     * 校验结果。
     *
     * @param childSessionId 子会话
     * @param stopReason 终止原因
     * @param summary 摘要
     * @param usage 用量
     * @param durationMillis 耗时
     * @param turns 轮数
     * @param toolCalls 工具调用数
     */
    public SubagentResult {
        Objects.requireNonNull(childSessionId, "childSessionId 不能为空");
        Objects.requireNonNull(stopReason, "stopReason 不能为空");
        Objects.requireNonNull(usage, "usage 不能为空");
        summary = summary == null ? "" : summary;
        if (summary.length() > MAX_SUMMARY_LENGTH) {
            summary = summary.substring(0, MAX_SUMMARY_LENGTH) + "\n…（摘要已截断）";
        }
        Guard.require(durationMillis >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "durationMillis 不能为负数");
        Guard.require(turns >= 0, DomainErrorCode.INVALID_ARGUMENT, "turns 不能为负数");
        Guard.require(toolCalls >= 0, DomainErrorCode.INVALID_ARGUMENT, "toolCalls 不能为负数");
    }

    /**
     * 子代理是否以失败告终。
     *
     * <p>只有正常结束（{@code STOP}）才算成功；长度截断、达到预算、取消、超时、
     * 错误都视为失败，父模型应据此判断是否换一种方式继续。</p>
     *
     * @return true 表示应以 isError 形式回写
     */
    public boolean isError() {
        return stopReason != RunStopReason.STOP;
    }

    /**
     * 渲染为给父模型阅读的文本。
     *
     * <p>固定头部包含子会话标识与统计，便于父模型在后续轮次引用；
     * 失败时把终止原因放在最前面，避免模型忽略。</p>
     *
     * @return 文本
     */
    public String render() {
        StringBuilder text = new StringBuilder();
        if (isError()) {
            text.append("子代理未正常完成（").append(stopReason.name()).append("）\n");
        }
        text.append(summary.isBlank() ? "（子代理没有输出正文）" : summary.strip());
        text.append("\n\n---\n子会话：").append(childSessionId)
                .append("｜轮数：").append(turns)
                .append("｜工具调用：").append(toolCalls)
                .append("｜耗时：").append(durationMillis).append("ms");
        return text.toString();
    }
}
