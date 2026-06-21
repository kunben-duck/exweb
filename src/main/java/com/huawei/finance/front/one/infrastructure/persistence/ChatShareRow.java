package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_share_t 持久化行对象。
 */
public class ChatShareRow {
    private String id;
    private String tenantId;
    private String ownerUserId;
    private String sourceSessionId;
    private String sourceUserMessageId;
    private String sourceAssistantMessageId;
    private String sourceRunId;
    private String title;
    private String scope;
    private String visibility;
    private String status;
    private Instant expiresAt;
    private Instant revokedAt;
    private String snapshotJson;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getSourceSessionId() {
        return sourceSessionId;
    }

    public void setSourceSessionId(String sourceSessionId) {
        this.sourceSessionId = sourceSessionId;
    }

    public String getSourceUserMessageId() {
        return sourceUserMessageId;
    }

    public void setSourceUserMessageId(String sourceUserMessageId) {
        this.sourceUserMessageId = sourceUserMessageId;
    }

    public String getSourceAssistantMessageId() {
        return sourceAssistantMessageId;
    }

    public void setSourceAssistantMessageId(String sourceAssistantMessageId) {
        this.sourceAssistantMessageId = sourceAssistantMessageId;
    }

    public String getSourceRunId() {
        return sourceRunId;
    }

    public void setSourceRunId(String sourceRunId) {
        this.sourceRunId = sourceRunId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(Instant revokedAt) {
        this.revokedAt = revokedAt;
    }

    public String getSnapshotJson() {
        return snapshotJson;
    }

    public void setSnapshotJson(String snapshotJson) {
        this.snapshotJson = snapshotJson;
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
