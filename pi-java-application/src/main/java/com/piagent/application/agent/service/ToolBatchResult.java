package com.piagent.application.agent.service;

import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.Guard;

/**
 * 一批工具调用的执行结果摘要。
 *
 * <p>每个调用无论是否真正执行，都必须产生一条 toolResult entry，保证
 * assistant 消息中的工具调用与结果始终配对。{@code executed} 只统计真正
 * 进入工具执行器的调用，用于 run 预算记账。</p>
 *
 * @param executed 实际执行的调用数
 * @param cancelled 批次中是否观察到取消信号
 * @param budgetExhausted 是否因工具预算不足而跳过调用
 */
record ToolBatchResult(int executed, boolean cancelled, boolean budgetExhausted) {

    ToolBatchResult {
        Guard.require(executed >= 0, DomainErrorCode.INVALID_ARGUMENT,
                "executed 不能为负数");
    }
}
