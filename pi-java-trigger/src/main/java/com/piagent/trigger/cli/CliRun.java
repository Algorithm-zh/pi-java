package com.piagent.trigger.cli;

import com.piagent.application.agent.readmodel.RunHandle;
import com.piagent.application.agent.service.AgentLoopService;
import com.piagent.application.agent.service.RunSubscription;
import com.piagent.application.governance.service.ApprovalApplicationService;
import com.piagent.domain.agent.RunEvent;
import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalDecision;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.shared.DomainErrorCode;
import com.piagent.domain.shared.DomainException;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * CLI 侧的一次活跃 run：事件队列 + 取消 + 审批决策。
 *
 * <p>事件回调在 run 线程上同步执行，因此这里只做非阻塞入队；渲染和审批提问
 * 由主线程消费队列完成，避免把终端 I/O 拖进 Agent Loop。</p>
 */
public final class CliRun {

    private final RunHandle handle;
    private final LinkedBlockingQueue<RunEvent> events;
    private final RunSubscription subscription;
    private final AgentLoopService loops;
    private final ApprovalApplicationService approvals;

    CliRun(RunHandle handle, LinkedBlockingQueue<RunEvent> events, RunSubscription subscription,
            AgentLoopService loops, ApprovalApplicationService approvals) {
        this.handle = handle;
        this.events = events;
        this.subscription = subscription;
        this.loops = loops;
        this.approvals = approvals;
    }

    /**
     * 等待下一条事件。
     *
     * @param timeout 最长等待
     * @return 事件；超时为 null
     * @throws InterruptedException 等待被中断
     */
    public RunEvent poll(Duration timeout) throws InterruptedException {
        return events.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * 请求取消当前 run。
     */
    public void abort() {
        try {
            loops.abort(handle.sessionId());
        } catch (DomainException error) {
            if (error.code() != DomainErrorCode.RUN_NOT_FOUND) {
                throw error;
            }
        }
    }

    /**
     * 提交审批决策。
     *
     * @param approvalId 审批标识
     * @param approve true 批准，false 拒绝
     * @param decidedBy 决策者
     * @param reason 拒绝原因，可为 null
     */
    public void decide(String approvalId, boolean approve, String decidedBy, String reason) {
        ApprovalId id;
        try {
            id = ApprovalId.parse(approvalId);
        } catch (IllegalArgumentException error) {
            throw new DomainException(DomainErrorCode.INVALID_ARGUMENT,
                    "审批 ID 不合法: " + approvalId, error);
        }
        approvals.decide(id, approve ? ApprovalDecision.APPROVE : ApprovalDecision.DENY,
                decidedBy, reason);
    }

    /**
     * 取消事件订阅；不取消 run 本身。
     */
    public void close() {
        subscription.close();
    }

    /**
     * run 标识。
     *
     * @return run ID
     */
    public RunId runId() {
        return handle.runId();
    }

    /**
     * 会话标识。
     *
     * @return session ID
     */
    public SessionId sessionId() {
        return handle.sessionId();
    }
}
