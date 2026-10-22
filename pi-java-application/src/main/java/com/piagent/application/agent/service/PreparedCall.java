package com.piagent.application.agent.service;

import com.piagent.domain.session.model.message.ToolCallBlock;
import com.piagent.domain.tool.model.ToolDescriptor;
import com.piagent.domain.tool.model.ToolResult;

/**
 * 预检后的单个工具调用。
 *
 * @param call 原始调用
 * @param descriptor 工具定义；未知工具为 null
 * @param rejection 预检拒绝结果；可执行或待审批时为 null
 * @param approvalRequired 是否需要在执行前等待人工审批
 * @param budgetBlocked 是否因预算被拒
 */
record PreparedCall(ToolCallBlock call, ToolDescriptor descriptor, ToolResult rejection,
        boolean approvalRequired, boolean budgetBlocked) {

    /**
     * 是否还需要执行（未被预检拒绝）。
     *
     * @return true 表示需要通过审批或直接调用
     */
    boolean requiresExecution() {
        return rejection == null;
    }

    /**
     * 是否可以直接执行（无需审批）。
     *
     * @return true 表示可以直接调用工具
     */
    boolean executable() {
        return rejection == null && !approvalRequired;
    }

    /**
     * 用预算拒绝结果替换当前调用。
     *
     * @param newRejection 新的拒绝结果
     * @param newBudgetBlocked 是否由预算导致
     * @return 新的预检结果
     */
    PreparedCall withRejection(ToolResult newRejection, boolean newBudgetBlocked) {
        return new PreparedCall(call, descriptor, newRejection, false, newBudgetBlocked);
    }
}
