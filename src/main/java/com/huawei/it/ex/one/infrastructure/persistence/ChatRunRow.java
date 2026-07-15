package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_run_t 的行模型。
 */
public class ChatRunRow {
    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String status;
    private String routeType;
    private String agentCode;
    private String runtimeProvider;
    private String runtimeSessionId;
    private String runMode;
    private String parentMessageId;
    private String userMessageId;
    private String assistantMessageId;
    private Long firstSeq;
    private Long lastSeq;
    private String cancelReason;
    private Instant startedAt;
    private Instant finishedAt;
    private String metadataJson;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRouteType() { return routeType; }
    public void setRouteType(String routeType) { this.routeType = routeType; }
    public String getAgentCode() { return agentCode; }
    public void setAgentCode(String agentCode) { this.agentCode = agentCode; }
    public String getRuntimeProvider() { return runtimeProvider; }
    public void setRuntimeProvider(String runtimeProvider) { this.runtimeProvider = runtimeProvider; }
    public String getRuntimeSessionId() { return runtimeSessionId; }
    public void setRuntimeSessionId(String runtimeSessionId) { this.runtimeSessionId = runtimeSessionId; }
    public String getRunMode() { return runMode; }
    public void setRunMode(String runMode) { this.runMode = runMode; }
    public String getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(String parentMessageId) { this.parentMessageId = parentMessageId; }
    public String getUserMessageId() { return userMessageId; }
    public void setUserMessageId(String userMessageId) { this.userMessageId = userMessageId; }
    public String getAssistantMessageId() { return assistantMessageId; }
    public void setAssistantMessageId(String assistantMessageId) { this.assistantMessageId = assistantMessageId; }
    public Long getFirstSeq() { return firstSeq; }
    public void setFirstSeq(Long firstSeq) { this.firstSeq = firstSeq; }
    public Long getLastSeq() { return lastSeq; }
    public void setLastSeq(Long lastSeq) { this.lastSeq = lastSeq; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
