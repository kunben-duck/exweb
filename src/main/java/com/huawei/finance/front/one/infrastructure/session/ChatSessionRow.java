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
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
