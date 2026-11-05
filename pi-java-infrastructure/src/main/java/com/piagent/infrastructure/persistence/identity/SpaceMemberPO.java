package com.piagent.infrastructure.persistence.identity;

import java.time.Instant;

/**
 * `pi_space_member` 表持久化对象。
 */
public class SpaceMemberPO {

    private String tenantId;
    private String userId;
    private String role;
    private Instant createdAt;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
