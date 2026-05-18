package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatReadCursor;
import java.util.Optional;

/**
 * 聊天事件消费游标 Redis 热缓存端口。
 *
 * <p>Redis 只用于快速读取最近 ack 位置；openGauss 仍是游标持久化事实源。
 * Redis 失败时，上层应退化为从 openGauss 读取或从 active run 起点重放。注意：
 * active run 的新渲染实例恢复不能直接跳到 read cursor，因为该 cursor 可能来自另一台设备。</p>
 */
public interface ChatReadCursorCache {
    /**
     * 读取用户在指定会话的已消费事件游标。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return Redis 中的游标；不存在或读取失败时为空。
     */
    Optional<ChatReadCursor> find(String tenantId, String userId, String sessionId);

    /**
     * 写入或刷新用户在指定会话的已消费事件游标。
     *
     * @param cursor 需要缓存的游标快照。
     */
    void put(ChatReadCursor cursor);
}
