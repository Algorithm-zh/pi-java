package com.piagent.infrastructure.persistence.model;

import java.time.Instant;

/**
 * `pi_tool_policy` 持久化对象。
 *
 * <p>只用于 MyBatis 行映射；枚举以字符串存取，兼容后续追加值。
 * {@code timeoutMs} 为 null 表示沿用工具定义中的默认超时。</p>
 */
public class ToolPolicyPO {

    private long id;
    private String scope;
    private String workspaceKey;
    private String toolName;
    private String effect;
    private String approvalMode;
    private String riskLevel;
    private Integer timeoutMs;
    private String configJson;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getWorkspaceKey() {
        return workspaceKey;
    }

    public void setWorkspaceKey(String workspaceKey) {
        this.workspaceKey = workspaceKey;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getEffect() {
        return effect;
    }

    public void setEffect(String effect) {
        this.effect = effect;
    }

    public String getApprovalMode() {
        return approvalMode;
    }

    public void setApprovalMode(String approvalMode) {
        this.approvalMode = approvalMode;
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(String riskLevel) {
        this.riskLevel = riskLevel;
    }

    public Integer getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(Integer timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public String getConfigJson() {
        return configJson;
    }

    public void setConfigJson(String configJson) {
        this.configJson = configJson;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
