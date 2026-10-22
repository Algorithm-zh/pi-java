package com.piagent.infrastructure.persistence.governance;

import com.piagent.domain.agent.RunId;
import com.piagent.domain.governance.model.ApprovalArguments;
import com.piagent.domain.governance.model.ApprovalContext;
import com.piagent.domain.governance.model.ApprovalId;
import com.piagent.domain.governance.model.ApprovalRequest;
import com.piagent.domain.governance.model.ApprovalStatus;
import com.piagent.domain.session.model.SessionId;
import com.piagent.domain.tool.model.ToolCallId;
import com.piagent.domain.tool.model.ToolName;
import com.piagent.domain.tool.model.ToolRisk;
import com.piagent.infrastructure.persistence.codec.ApprovalArgumentsCodec;
import java.util.Map;

/**
 * `pi_approval_request` 行与审批聚合之间的转换。
 *
 * <p>枚举、ULID 与风险等级按名称/字符串映射；遇到非法值视为数据损坏并
 * 直接失败，不静默降级成其他状态。</p>
 */
public final class ApprovalPoConverter {

    private final ApprovalArgumentsCodec argumentsCodec;

    /**
     * 创建转换器。
     *
     * @param argumentsCodec 参数编解码
     */
    public ApprovalPoConverter(ApprovalArgumentsCodec argumentsCodec) {
        this.argumentsCodec = argumentsCodec;
    }

    /**
     * 聚合转持久化对象。
     *
     * @param request 审批聚合
     * @return 持久化对象
     */
    public ApprovalPO toPo(ApprovalRequest request) {
        ApprovalPO po = new ApprovalPO();
        po.setId(request.id().toString());
        po.setSessionId(request.sessionId().toString());
        po.setRunId(request.runId().toString());
        po.setToolCallId(request.toolCallId().toString());
        po.setToolName(request.toolName().toString());
        po.setArgsDigest(request.context().arguments().digest());
        po.setArgsRedactedJson(argumentsCodec.encode(request.context().arguments().redacted()));
        po.setRiskLevel(request.riskLevel().name());
        po.setStatus(request.status().name());
        po.setReason(request.reason());
        po.setDecidedBy(request.decidedBy());
        po.setDecidedAt(request.decidedAt());
        po.setExpiresAt(request.expiresAt());
        po.setCreatedAt(request.createdAt());
        return po;
    }

    /**
     * 持久化对象转聚合。
     *
     * @param po 持久化对象
     * @return 审批聚合
     */
    public ApprovalRequest toDomain(ApprovalPO po) {
        Map<String, Object> redacted = argumentsCodec.decode(po.getArgsRedactedJson());
        ApprovalContext context = new ApprovalContext(
                SessionId.parse(po.getSessionId()),
                RunId.parse(po.getRunId()),
                new ToolCallId(po.getToolCallId()),
                new ToolName(po.getToolName()),
                new ApprovalArguments(po.getArgsDigest(), redacted),
                ToolRisk.valueOf(po.getRiskLevel()));
        return new ApprovalRequest(
                ApprovalId.parse(po.getId()),
                context,
                ApprovalStatus.valueOf(po.getStatus()),
                po.getReason(),
                po.getDecidedBy(),
                po.getDecidedAt(),
                po.getExpiresAt(),
                po.getCreatedAt());
    }
}
