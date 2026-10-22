package com.piagent.application.agent.service;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.session.model.ChatSession;
import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.tool.port.ToolRegistry;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * 一次工具批次执行所需的上下文。
 *
 * <p>把会话、运行、工具快照、事件、取消查询与剩余预算收敛为一个不可变
 * 参数对象，避免协调器与流水线之间传递七个平铺参数。</p>
 *
 * @param session 当前会话快照
 * @param runId 当前运行标识
 * @param calls 模型返回的工具调用
 * @param registry 本次 run 的工具快照
 * @param events 事件发布器
 * @param cancelled 取消查询
 * @param remainingCalls 剩余可执行工具次数
 */
public record ToolBatchContext(ChatSession session, RunId runId, List<ToolCallBlock> calls,
        ToolRegistry registry, RunEventPublisher events, BooleanSupplier cancelled,
        int remainingCalls) {

    /**
     * 校验批次上下文。
     *
     * @param session 会话
     * @param runId 运行标识
     * @param calls 工具调用
     * @param registry 工具快照
     * @param events 事件发布器
     * @param cancelled 取消查询
     * @param remainingCalls 剩余预算
     */
    public ToolBatchContext {
        Objects.requireNonNull(session, "session 不能为空");
        Objects.requireNonNull(runId, "runId 不能为空");
        Objects.requireNonNull(registry, "registry 不能为空");
        Objects.requireNonNull(events, "events 不能为空");
        Objects.requireNonNull(cancelled, "cancelled 不能为空");
        calls = List.copyOf(Objects.requireNonNull(calls, "calls 不能为空"));
        if (remainingCalls < 0) {
            throw new IllegalArgumentException("remainingCalls 不能为负数");
        }
    }
}
