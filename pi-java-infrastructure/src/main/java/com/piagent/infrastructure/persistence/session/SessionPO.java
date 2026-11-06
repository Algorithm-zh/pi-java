package com.piagent.infrastructure.persistence.session;

import java.time.Instant;

/**
 * `pi_session` 表的 MyBatis 持久化对象。
 *
 * <p>字段与列一一对应；业务校验全部在聚合根完成，PO 只负责数据搬运。
 */
public class SessionPO {

    private String id;
    private String parentSessionId;
    private String rootSessionId;
    private String forkFromEntryId;
    private int depth;
    private String origin;
    private String title;
    private String cwd;
    private String modelProfileId;
    private String thinkingLevel;
    private String sandboxJson;
    private String status;
    private String leafEntryId;
    private long version;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getParentSessionId() {
        return parentSessionId;
    }

    public void setParentSessionId(String parentSessionId) {
        this.parentSessionId = parentSessionId;
    }

    public String getRootSessionId() {
        return rootSessionId;
    }

    public void setRootSessionId(String rootSessionId) {
        this.rootSessionId = rootSessionId;
    }

    public String getForkFromEntryId() {
        return forkFromEntryId;
    }

    public void setForkFromEntryId(String forkFromEntryId) {
        this.forkFromEntryId = forkFromEntryId;
    }

    public int getDepth() {
        return depth;
    }

    public void setDepth(int depth) {
        this.depth = depth;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCwd() {
        return cwd;
    }

    public void setCwd(String cwd) {
        this.cwd = cwd;
    }

    public String getModelProfileId() {
        return modelProfileId;
    }

    public void setModelProfileId(String modelProfileId) {
        this.modelProfileId = modelProfileId;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    public String getSandboxJson() {
        return sandboxJson;
    }

    public void setSandboxJson(String sandboxJson) {
        this.sandboxJson = sandboxJson;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getLeafEntryId() {
        return leafEntryId;
    }

    public void setLeafEntryId(String leafEntryId) {
        this.leafEntryId = leafEntryId;
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
