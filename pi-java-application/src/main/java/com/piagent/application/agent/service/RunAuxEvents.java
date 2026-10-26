package com.piagent.application.agent.service;

import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.session.model.payload.PlanPayload;
import com.piagent.domain.session.model.payload.VerificationPayload;
import org.springframework.stereotype.Component;

/**
 * 把不属于单个工具调用的辅助事件（计划、验证）发布到活跃 run 的通道。
 *
 * <p>计划与验证由模型工具或自动步骤产生，调用点不在 Agent Loop 内，
 * 但前端仍需要与 run 内事件相同的 SSE 通道；没有活跃 run 时静默忽略。</p>
 */
@Component
public class RunAuxEvents {

    private final RunRuntime runtime;
    private final RunEventHub hub;

    /**
     * 创建辅助事件发布器。
     *
     * @param runtime 运行运行时
     * @param hub 事件中心
     */
    public RunAuxEvents(RunRuntime runtime, RunEventHub hub) {
        this.runtime = runtime;
        this.hub = hub;
    }

    /**
     * 发布待办快照。
     *
     * @param sessionId 会话标识
     * @param payload 计划载荷
     */
    public void planUpdated(SessionId sessionId, PlanPayload payload) {
        runtime.findActiveBySession(sessionId)
                .ifPresent(run -> new RunEventPublisher(hub, run.id()).planUpdated(payload));
    }

    /**
     * 发布自动验证结果。
     *
     * @param sessionId 会话标识
     * @param payload 验证载荷
     */
    public void verificationCompleted(SessionId sessionId, VerificationPayload payload) {
        runtime.findActiveBySession(sessionId)
                .ifPresent(run -> new RunEventPublisher(hub, run.id())
                        .verificationCompleted(payload));
    }
}
