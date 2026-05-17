package com.huawei.finance.front.one.infrastructure.runtime;

import java.time.Instant;

/**
 * fin_ex_runtime_binding_t 表行对象。
 */
public class RuntimeBindingRow {
    private String id;
    private String tenantId;
    private String userId;
    private String chatSessionId;
    private String provider;
    private String runtimeSessionId;
    private String status;
    private String lastRunId;
    private Instant expiresAt;
    private String metadataJson;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getChatSessionId() { return chatSessionId; }
    public void setChatSessionId(String chatSessionId) { this.chatSessionId = chatSessionId; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getRuntimeSessionId() { return runtimeSessionId; }
    public void setRuntimeSessionId(String runtimeSessionId) { this.runtimeSessionId = runtimeSessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastRunId() { return lastRunId; }
    public void setLastRunId(String lastRunId) { this.lastRunId = lastRunId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
