/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.memory;

import com.huawei.it.ex.one.domain.chat.ChatMessageFeedback;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 聊天消息反馈事实源端口。
 */
public interface ChatFeedbackRepository {
    /**
     * 保存用户反馈。
     *
     * @param feedback 反馈事实。
     * @return 已保存的反馈。
     */
    ChatMessageFeedback save(ChatMessageFeedback feedback);

    /**
     * 取消当前用户对某条消息的当前反馈。
     *
     * <p>取消是幂等操作：如果历史上没有反馈记录，仓储实现应返回空，由应用层构造
     * CANCELLED 响应；如果存在反馈记录，则把当前状态置为 CANCELLED。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 被取消反馈的消息标识。
     * @param cancelledAt 取消时间。
     * @return 被更新后的反馈记录；不存在历史反馈时为空。
     */
    Optional<ChatMessageFeedback> cancel(String tenantId, String userId, String messageId, Instant cancelledAt);

    /**
     * 查询当前用户对一组消息仍然有效的反馈。
     *
     * <p>只返回 status=ACTIVE 的记录，用于历史消息装配按钮高亮状态；已取消反馈不应暴露给历史消息。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 消息所属会话标识。
     * @param messageIds 待查询消息标识集合。
     * @return key 为 messageId 的当前有效反馈。
     */
    Map<String, ChatMessageFeedback> findActiveByMessages(
            String tenantId, String userId, String sessionId, Collection<String> messageIds);

    /**
     * 查询当前用户对单条消息的反馈记录，包括已取消状态。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 消息标识。
     * @return 当前反馈记录；不存在时为空。
     */
    Optional<ChatMessageFeedback> findByMessage(String tenantId, String userId, String messageId);
}
