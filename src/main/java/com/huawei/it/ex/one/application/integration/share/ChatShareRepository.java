package com.huawei.it.ex.one.application.integration.share;

import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatSharePage;

import java.time.Instant;
import java.util.Optional;

/**
 * 聊天消息分享仓储端口。
 */
public interface ChatShareRepository {
    ChatShare save(ChatShare share);

    Optional<ChatShare> findById(String shareId);

    ChatSharePage pageByOwner(String tenantId, String ownerUserId, int curPage, int pageSize);

    /**
     * 撤销指定用户在指定会话下创建的仍处于 ACTIVE 状态的分享。
     *
     * <p>删除会话时使用该方法收敛分享访问面。条件中显式包含 ownerUserId，
     * 避免在极端 ID 碰撞或测试数据复用场景下误撤销同租户其他用户的分享。</p>
     */
    void revokeActiveBySession(String tenantId, String ownerUserId, String sessionId, Instant revokedAt);
}
