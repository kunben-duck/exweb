package com.huawei.finance.front.one.domain.chat;

import java.time.Instant;

/**
 * 用户在某个聊天会话中的事件消费游标。
 *
 * <p>游标不是聊天事件本身，而是当前用户某个连接已经处理完成的最大事件序号。
 * 它适合展示用户消费进度、辅助诊断和非 active 场景减少重复事件；新页签、新浏览器
 * 或新电脑恢复正在输出的 active run 时，仍应从 active run 的首个事件之前补发，
 * 不能把该值当作当前渲染实例已经展示到的位置。</p>
 *
 * @param id 游标记录主键。
 * @param tenantId 租户标识，来自服务端身份上下文。
 * @param userId 用户标识，来自服务端身份上下文。
 * @param sessionId 前端聊天会话标识。
 * @param lastConsumedSeq 当前用户已经处理完成的最大事件序号。
 * @param updatedAt 游标最后更新时间。
 */
public record ChatReadCursor(
        String id,
        String tenantId,
        String userId,
        String sessionId,
        long lastConsumedSeq,
        Instant updatedAt
) {
    public ChatReadCursor {
        lastConsumedSeq = Math.max(0L, lastConsumedSeq);
        updatedAt = updatedAt == null ? Instant.EPOCH : updatedAt;
    }
}
