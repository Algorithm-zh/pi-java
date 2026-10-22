package com.piagent.application.governance.service;

import com.piagent.application.agent.service.RunEventHub;
import com.piagent.domain.agent.RunEventType;
import com.piagent.domain.governance.model.ApprovalRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 审批 SSE 事件发布器。
 *
 * <p>事件只包含展示所需字段：请求事件带脱敏参数与风险级别，决策事件带终态、
 * 决策者与意见。原始参数与摘要不会进入事件通道。</p>
 */
@Component
public class ApprovalEventPublisher {

    private final RunEventHub hub;

    /**
     * 创建发布器。
     *
     * @param hub run 事件中心
     */
    public ApprovalEventPublisher(RunEventHub hub) {
        this.hub = hub;
    }

    /**
     * 发布待审批请求。
     *
     * @param request 待审批请求
     */
    public void requested(ApprovalRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approvalId", request.id().toString());
        data.put("sessionId", request.sessionId().toString());
        data.put("runId", request.runId().toString());
        data.put("toolCallId", request.toolCallId().toString());
        data.put("toolName", request.toolName().toString());
        data.put("riskLevel", request.riskLevel().name());
        data.put("arguments", request.context().arguments().redacted());
        data.put("createdAt", request.createdAt().toString());
        data.put("expiresAt", request.expiresAt().toString());
        hub.publish(request.runId(), RunEventType.APPROVAL_REQUESTED, data);
    }

    /**
     * 发布审批决策。
     *
     * @param request 终态审批
     */
    public void resolved(ApprovalRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("approvalId", request.id().toString());
        data.put("toolCallId", request.toolCallId().toString());
        data.put("toolName", request.toolName().toString());
        data.put("status", request.status().name());
        data.put("decidedBy", request.decidedBy());
        data.put("decidedAt", request.decidedAt().toString());
        if (request.reason() != null) {
            data.put("reason", request.reason());
        }
        hub.publish(request.runId(), RunEventType.APPROVAL_RESOLVED, data);
    }
}
