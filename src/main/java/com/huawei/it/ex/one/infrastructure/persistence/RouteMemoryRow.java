package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_route_memory_t 表行模型。
 */
public class RouteMemoryRow {
    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String itemType;
    private String status;
    private String queryText;
    private String intentId;
    private String intentName;
    private String domainAgentId;
    private String routeSource;
    private String clarifyQuestion;
    private String clarificationType;
    private String sourceRunId;
    private String interactionId;
    private String payloadJson;
    private Instant foldedAt;
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
    public String getItemType() { return itemType; }
    public void setItemType(String itemType) { this.itemType = itemType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getQueryText() { return queryText; }
    public void setQueryText(String queryText) { this.queryText = queryText; }
    public String getIntentId() { return intentId; }
    public void setIntentId(String intentId) { this.intentId = intentId; }
    public String getIntentName() { return intentName; }
    public void setIntentName(String intentName) { this.intentName = intentName; }
    public String getDomainAgentId() { return domainAgentId; }
    public void setDomainAgentId(String domainAgentId) { this.domainAgentId = domainAgentId; }
    public String getRouteSource() { return routeSource; }
    public void setRouteSource(String routeSource) { this.routeSource = routeSource; }
    public String getClarifyQuestion() { return clarifyQuestion; }
    public void setClarifyQuestion(String clarifyQuestion) { this.clarifyQuestion = clarifyQuestion; }
    public String getClarificationType() { return clarificationType; }
    public void setClarificationType(String clarificationType) { this.clarificationType = clarificationType; }
    public String getSourceRunId() { return sourceRunId; }
    public void setSourceRunId(String sourceRunId) { this.sourceRunId = sourceRunId; }
    public String getInteractionId() { return interactionId; }
    public void setInteractionId(String interactionId) { this.interactionId = interactionId; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public Instant getFoldedAt() { return foldedAt; }
    public void setFoldedAt(Instant foldedAt) { this.foldedAt = foldedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
