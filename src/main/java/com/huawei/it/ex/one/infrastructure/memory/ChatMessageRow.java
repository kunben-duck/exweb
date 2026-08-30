/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import java.time.Instant;

/**
 * fin_ex_chat_message_t 表的 MyBatis 行模型。
 *
 * <p>基础设施层使用可变对象承接 MyBatis 映射，再转换为领域层不可变 ChatMessage。</p>
 */
public class ChatMessageRow {
    private String id;
    private String tenantId;
    private String userId;
    private String sessionId;
    private String parentMessageId;
    private Long nodeOrder;
    private Integer treeDepth;
    private Integer siblingIndex;
    private String role;
    private String content;
    private Integer tokenCount;
    private String runId;
    private String originType;
    private Boolean locked;
    private String sourceSessionId;
    private String sourceMessageId;
    private String editedFromMessageId;
    private String regeneratedFromMessageId;
    private String metadataJson;
    private Instant createdAt;

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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getParentMessageId() { return parentMessageId; }
    public void setParentMessageId(String parentMessageId) { this.parentMessageId = parentMessageId; }
    public Long getNodeOrder() { return nodeOrder; }
    public void setNodeOrder(Long nodeOrder) { this.nodeOrder = nodeOrder; }
    public Integer getTreeDepth() { return treeDepth; }
    public void setTreeDepth(Integer treeDepth) { this.treeDepth = treeDepth; }
    public Integer getSiblingIndex() { return siblingIndex; }
    public void setSiblingIndex(Integer siblingIndex) { this.siblingIndex = siblingIndex; }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getTokenCount() {
        return tokenCount;
    }

    public void setTokenCount(Integer tokenCount) {
        this.tokenCount = tokenCount;
    }

    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public String getOriginType() { return originType; }
    public void setOriginType(String originType) { this.originType = originType; }
    public Boolean getLocked() { return locked; }
    public void setLocked(Boolean locked) { this.locked = locked; }
    public String getSourceSessionId() { return sourceSessionId; }
    public void setSourceSessionId(String sourceSessionId) { this.sourceSessionId = sourceSessionId; }
    public String getSourceMessageId() { return sourceMessageId; }
    public void setSourceMessageId(String sourceMessageId) { this.sourceMessageId = sourceMessageId; }
    public String getEditedFromMessageId() { return editedFromMessageId; }
    public void setEditedFromMessageId(String editedFromMessageId) { this.editedFromMessageId = editedFromMessageId; }
    public String getRegeneratedFromMessageId() { return regeneratedFromMessageId; }
    public void setRegeneratedFromMessageId(String regeneratedFromMessageId) { this.regeneratedFromMessageId = regeneratedFromMessageId; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
