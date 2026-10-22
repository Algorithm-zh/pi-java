package com.piagent.trigger.http.governance;

import com.piagent.domain.governance.model.ApprovalRequest;
import java.time.Instant;
import java.util.Map;

/**
 * 审批请求 HTTP 响应。
 *
 * <p>{@code arguments} 是脱敏副本，密钥类参数已被替换为掩码；
 * {@code argumentsDigest} 是原始参数的 SHA-256，用于校验界面展示与真正
 * 执行的内容一致。时间统一为 ISO-8601 字符串，未决策字段为 null。</p>
 *
 * @param approvalId 审批标识
 * @param sessionId 会话标识
 * @param runId 运行标识
 * @param toolCallId 工具调用标识
 * @param toolName 工具名
 * @param riskLevel 风险级别
 * @param argumentsDigest 原始参数摘要
 * @param arguments 脱敏参数
 * @param status 审批状态
 * @param reason 决策意见，可为 null
 * @param decidedBy 决策者，PENDING 时为 null
 * @param decidedAt 决策时间，PENDING 时为 null
 * @param expiresAt 过期时间
 * @param createdAt 创建时间
 */
public record ApprovalResponse(String approvalId, String sessionId, String runId, String toolCallId,
        String toolName, String riskLevel, String argumentsDigest, Map<String, Object> arguments,
        String status, String reason, String decidedBy, String decidedAt, String expiresAt,
        String createdAt) {

    /**
     * 从聚合构造响应。
     *
     * @param request 审批请求
     * @return 响应
     */
    public static ApprovalResponse from(ApprovalRequest request) {
        return new ApprovalResponse(request.id().toString(), request.sessionId().toString(),
                request.runId().toString(), request.toolCallId().toString(),
                request.toolName().toString(), request.riskLevel().name(),
                request.context().arguments().digest(), request.context().arguments().redacted(),
                request.status().name(), request.reason(), request.decidedBy(),
                textOf(request.decidedAt()), request.expiresAt().toString(),
                request.createdAt().toString());
    }

    private static String textOf(Instant value) {
        return value == null ? null : value.toString();
    }
}
