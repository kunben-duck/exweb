package com.huawei.finance.front.one.infrastructure.memory.mybatis;

import java.time.Instant;

/**
 * fin_ex_conversation_summary_t 的行模型。
 */
public class ConversationSummaryRow {
    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String summaryText;
    private Long messageFromSeq;
    private Long messageToSeq;
    private Instant createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public Long getMessageFromSeq() { return messageFromSeq; }
    public void setMessageFromSeq(Long messageFromSeq) { this.messageFromSeq = messageFromSeq; }
    public Long getMessageToSeq() { return messageToSeq; }
    public void setMessageToSeq(Long messageToSeq) { this.messageToSeq = messageToSeq; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
