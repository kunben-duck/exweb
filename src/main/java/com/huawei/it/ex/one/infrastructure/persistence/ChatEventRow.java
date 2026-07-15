package com.huawei.it.ex.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_event_t 的行模型。
 *
 * <p>Row 对象只服务 MyBatis 映射；上层统一转换为 ChatEvent/StoredChatEvent。</p>
 */
public class ChatEventRow {
    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String runId;
    private long seq;
    private String eventType;
    private String payloadJson;
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public long getSeq() { return seq; }
    public void setSeq(long seq) { this.seq = seq; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
