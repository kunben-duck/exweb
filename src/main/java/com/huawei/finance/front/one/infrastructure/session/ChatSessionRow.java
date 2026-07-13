package com.huawei.finance.front.one.infrastructure.session;

import java.time.Instant;

/**
 * fin_ex_chat_session_t 的行模型。
 */
public class ChatSessionRow {
    /** 会话主键。 */
    private String id;
    /** 租户标识。 */
    private String tenantId;
    /** 用户标识。 */
    private String userId;
    /** 会话标题。 */
    private String title;
    /** 会话状态。 */
    private String status;
    /** 会话来源渠道。 */
    private String channel;
    /** 会话所属应用标识。 */
    private String appId;
    /** 会话所属应用名称快照。 */
    private String appName;
    /** 当前 active path 叶子消息。 */
    private String currentLeafMessageId;
    /** 分支族根会话。 */
    private String rootSessionId;
    /** 分支来源会话。 */
    private String branchSourceSessionId;
    /** 分支来源消息。 */
    private String branchSourceMessageId;
    /** 当前会话最大消息节点序号。 */
    private Long lastNodeOrder;
    /** 会话扩展元数据 JSON。 */
    private String metadataJson;
    /** 创建时间。 */
    private Instant createdAt;
    /** 最后更新时间。 */
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAppName() { return appName; }
    public void setAppName(String appName) { this.appName = appName; }
    public String getCurrentLeafMessageId() { return currentLeafMessageId; }
    public void setCurrentLeafMessageId(String currentLeafMessageId) { this.currentLeafMessageId = currentLeafMessageId; }
    public String getRootSessionId() { return rootSessionId; }
    public void setRootSessionId(String rootSessionId) { this.rootSessionId = rootSessionId; }
    public String getBranchSourceSessionId() { return branchSourceSessionId; }
    public void setBranchSourceSessionId(String branchSourceSessionId) { this.branchSourceSessionId = branchSourceSessionId; }
    public String getBranchSourceMessageId() { return branchSourceMessageId; }
    public void setBranchSourceMessageId(String branchSourceMessageId) { this.branchSourceMessageId = branchSourceMessageId; }
    public Long getLastNodeOrder() { return lastNodeOrder; }
    public void setLastNodeOrder(Long lastNodeOrder) { this.lastNodeOrder = lastNodeOrder; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
