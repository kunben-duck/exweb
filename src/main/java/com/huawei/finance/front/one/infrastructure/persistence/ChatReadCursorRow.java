package com.huawei.finance.front.one.infrastructure.persistence;

import java.time.Instant;

/**
 * fin_ex_chat_read_cursor_t 的行模型。
 *
 * <p>该对象只用于 MyBatis 与 openGauss 之间的数据映射，不承载业务判断。业务层应使用
 * {@link com.huawei.finance.front.one.domain.chat.ChatReadCursor}。</p>
 */
public class ChatReadCursorRow {
    /** 游标记录主键。 */
    private String id;
    /** 租户标识，来自服务端身份上下文。 */
    private String tenantId;
    /** 用户标识，来自服务端身份上下文。 */
    private String userId;
    /** 前端聊天会话标识。 */
    private String sessionId;
    /** 当前用户已经确认消费完成的最大聊天事件 seq。 */
    private Long lastConsumedSeq;
    /** 游标最后一次持久化更新时间。 */
    private Instant updatedAt;

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

    public Long getLastConsumedSeq() {
        return lastConsumedSeq;
    }

    public void setLastConsumedSeq(Long lastConsumedSeq) {
        this.lastConsumedSeq = lastConsumedSeq;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
