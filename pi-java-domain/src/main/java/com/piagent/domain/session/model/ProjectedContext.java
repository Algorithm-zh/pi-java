package com.piagent.domain.session.model;

import com.piagent.domain.session.model.message.AgentMessage;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;
import java.util.List;
import java.util.Objects;

/**
 * 从 entry 日志投影出的 LLM 上下文。
 *
 * <p>纯值对象：投影器不访问数据库，相同 entry 序列必得相同结果，便于黄金样本测试。
 *
 * @param messages 按时间顺序排列的上下文消息
 * @param trace 参与投影的 entry ID，包含压缩摘要 entry
 * @param estimatedTokens 估算 token 总量
 */
public record ProjectedContext(List<AgentMessage> messages, List<EntryId> trace, int estimatedTokens) {

    /**
     * 校验并冻结投影结果。
     *
     * @param messages 消息
     * @param trace entry 轨迹
     * @param estimatedTokens token 估算
     */
    public ProjectedContext {
        Objects.requireNonNull(messages, "messages 不能为空");
        Objects.requireNonNull(trace, "trace 不能为空");
        Guard.require(estimatedTokens >= 0, DomainErrorCode.INVALID_ARGUMENT, "estimatedTokens 不能为负数");
        messages = List.copyOf(messages);
        trace = List.copyOf(trace);
    }

    /**
     * 空上下文。
     *
     * @return 不含任何消息的上下文
     */
    public static ProjectedContext empty() {
        return new ProjectedContext(List.of(), List.of(), 0);
    }

    /**
     * 是否没有上下文消息。
     *
     * @return true 表示空上下文
     */
    public boolean isEmpty() {
        return messages.isEmpty();
    }
}
