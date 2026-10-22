package com.piagent.application.agent.service;

import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalRequest;
import java.util.function.BooleanSupplier;

/**
 * 工具审批门面。
 *
 * <p>协调器只依赖这个窄接口：发起待办、阻塞等待终态、感知 run 取消。
 * 具体实现负责持久化、会话状态、SSE 事件与超时；测试可以替换为即时决策的替身。</p>
 */
public interface ApprovalGate {

    /**
     * 等待一次工具审批结束。
     *
     * <p>方法返回时审批一定处于终态（批准、拒绝、过期或取消）。若等待期间
     * 收到取消信号，实现应把待办迁移为 CANCELLED 后再返回。</p>
     *
     * @param context 被审批的工具调用
     * @param cancelled 运行取消查询
     * @return 终态审批
     */
    ApprovalRequest await(ApprovalContext context, BooleanSupplier cancelled);
}
