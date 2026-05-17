package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.util.List;
import java.util.Optional;

/**
 * 聊天会话事实源端口。
 *
 * <p>所有查询都应通过 tenantId/userId 进行归属过滤；无 owner 条件的方法仅供内部已校验场景使用。</p>
 */
public interface SessionRepository {
    /**
     * 按会话 ID 查询会话。
     *
     * @param sessionId 前端聊天会话标识。
     * @return 会话快照；不存在时为空。
     */
    Optional<ChatSession> findById(String sessionId);

    /**
     * 按租户、用户和会话 ID 查询当前用户拥有的会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @return 当前用户拥有的会话；不存在或不属于当前用户时为空。
     */
    Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId);

    /**
     * 查询当前用户全部会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @return 当前用户会话列表。
     */
    List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId);

    /**
     * 分页查询当前用户会话。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param cursor 上一页返回的游标。
     * @param limit 最大返回条数。
     * @return 会话分页结果。
     */
    ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit);

    /**
     * 保存或更新会话快照。
     *
     * @param session 会话快照。
     * @return 已保存的会话。
     */
    ChatSession save(ChatSession session);
}
