package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Instant;

/**
 * fin_ex_chat_message_part_t 表的 MyBatis 行模型。
 */
public class ChatMessagePartRow {
    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String messageId;
    private String runId;
    private String partType;
    private String sourceType;
    private String contentText;
    private String title;
    private String status;
    private String channel;
    private String displayHint;
    private Boolean visible;
    private String payloadJson;
    private Integer partOrder;
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getPartType() { return partType; }
    public void setPartType(String partType) { this.partType = partType; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getContentText() { return contentText; }
    public void setContentText(String contentText) { this.contentText = contentText; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getDisplayHint() { return displayHint; }
    public void setDisplayHint(String displayHint) { this.displayHint = displayHint; }
    public Boolean getVisible() { return visible; }
    public void setVisible(Boolean visible) { this.visible = visible; }
    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }
    public Integer getPartOrder() { return partOrder; }
    public void setPartOrder(Integer partOrder) { this.partOrder = partOrder; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
