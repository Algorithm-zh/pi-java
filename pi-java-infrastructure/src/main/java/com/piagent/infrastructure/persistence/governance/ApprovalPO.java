package com.piagent.infrastructure.persistence.governance;

import java.time.Instant;

/**
 * `pi_approval_request` 持久化对象。
 *
 * <p>只用于 MyBatis 行映射；枚举与时间保持原始列类型，转换由
 * {@link ApprovalPoConverter} 完成。</p>
 */
public class ApprovalPO {

    private String id;
    private String sessionId;
    private String runId;
    private String toolCallId;
    private String toolName;
    private String argsDigest;
    private String argsRedactedJson;
    private String riskLevel;
    private String status;
    private String reason;
    private String decidedBy;
    private Instant decidedAt;
    private Instant expiresAt;
    private Instant createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArgsDigest() {
        return argsDigest;
    }

    public void setArgsDigest(String argsDigest) {
        this.argsDigest = argsDigest;
    }

    public String getArgsRedactedJson() {
        return argsRedactedJson;
    }

    public void setArgsRedactedJson(String argsRedactedJson) {
        this.argsRedactedJson = argsRedactedJson;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public void setDecidedBy(String decidedBy) {
        this.decidedBy = decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }

    public void setDecidedAt(Instant decidedAt) {
        this.decidedAt = decidedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
