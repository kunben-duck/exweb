package com.huawei.finance.front.one.infrastructure.memory;

import java.time.Instant;

/**
 * fin_ex_chat_message_attachment_t 表的 MyBatis 行模型。
 */
public class ChatMessageAttachmentRow {
    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String messageId;
    private String documentId;
    private Integer attachmentOrder;
    private String name;
    private String contentType;
    private Long sizeBytes;
    private String sourceAttachmentId;
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
    public String getDocumentId() { return documentId; }
    public void setDocumentId(String documentId) { this.documentId = documentId; }
    public Integer getAttachmentOrder() { return attachmentOrder; }
    public void setAttachmentOrder(Integer attachmentOrder) { this.attachmentOrder = attachmentOrder; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSourceAttachmentId() { return sourceAttachmentId; }
    public void setSourceAttachmentId(String sourceAttachmentId) { this.sourceAttachmentId = sourceAttachmentId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
