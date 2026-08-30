/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.memory;

import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessageVersionCandidate;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 聊天消息仓储，也可作为短期最近问答记忆的数据来源。
 *
 * <p>会话历史消息始终是可审计事实；短期记忆是否读取最近消息由 MemoryApplicationService 配置决定。
 * application 层只表达保存和读取最近会话消息的能力，不关心底层是 Redis、数据库还是组合存储。</p>
 */
public interface ChatMessageRepository {
    /**
     * 保存聊天消息。
     *
     * @param message 用户或助手完整消息。
     * @return 已保存的消息。
     */
    ChatMessage save(ChatMessage message);

    /**
     * 更新已有 assistant 消息的正文、run 关联和元数据。
     *
     * @param message 已存在的 assistant 消息快照。
     * @return 更新后的消息。
     */
    default ChatMessage updateAssistantMessage(ChatMessage message) {
        return message;
    }

    /** Updates only assistant metadata while preserving content, run association, timestamps and parts. */
    default ChatMessage updateAssistantMetadata(ChatMessage existing, String metadataJson) {
        return existing == null ? null : existing.withMetadataJson(metadataJson);
    }

    /**
     * 按租户、用户和会话读取最近消息，避免跨用户会话记忆串用。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 前端聊天会话标识。
     * @param limit 最大返回条数。
     * @return 最近消息列表，通常按时间倒序返回给 MemoryContext 使用。
     */
    List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit);

    /**
     * 按指定消息树叶子读取最近消息。
     *
     * <p>默认实现兼容不支持消息树的仓储；支持 active path 的实现应覆盖该方法，确保编辑、重新生成和
     * 分支切换后的短期上下文只来自当前路径。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param leafMessageId 当前 active path 的叶子消息；为空时由仓储读取会话当前叶子。
     * @param limit 最大返回条数。
     * @return 按上下文阅读顺序排列的最近消息。
     */
    default List<ChatMessage> findRecentMessages(
            String tenantId, String userId, String sessionId, String leafMessageId, int limit) {
        return findRecentMessages(tenantId, userId, sessionId, limit);
    }

    /**
     * 分页查询历史消息，供前端会话详情页使用。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param cursor 上一页返回的游标。
     * @param limit 最大返回条数。
     * @return 按时间正序排列的消息分页。
     */
    ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit);

    /**
     * 批量查询每个会话的第一条 assistant 完整消息。
     *
     * <p>该方法用于会话分页列表摘要装配，必须以 tenantId/userId/sessionIds 联合过滤，避免跨租户、
     * 跨用户读取其他会话内容。返回 Map 的 key 为 sessionId。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionIds 当前页会话 ID 列表。
     * @return sessionId 到第一条 assistant 消息的映射。
     */
    default Map<String, ChatMessage> findFirstAssistantMessagesBySessionIds(
            String tenantId, String userId, List<String> sessionIds) {
        return Map.of();
    }

    /**
     * 查询指定 leaf 的可见消息路径。
     *
     * <p>默认实现只使用 owner/session/cursor/limit 查询普通历史分页，不理解 leafMessageId。
     * 支持消息树路径查询的实现应覆盖该方法。</p>
     *
     * @param query 历史消息分页查询条件。
     * @return 符合 query 的消息分页；覆盖实现可返回 root 到指定 leaf 的 active path。
     */
    default ChatMessagePage pageMessages(ChatMessagePageQuery query) {
        return pageMessages(query.tenantId(), query.userId(), query.sessionId(), query.cursor(), query.limit());
    }

    /**
     * 查询当前会话内完整可见消息树节点。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 当前用户当前会话内全部 user/assistant 完整消息，按 nodeOrder 排序。
     */
    default List<ChatMessage> findAllBySession(String tenantId, String userId, String sessionId) {
        return pageMessages(new ChatMessagePageQuery(tenantId, userId, sessionId, null, null, Integer.MAX_VALUE)).items();
    }

    /**
     * 查询当前会话内全部可见消息树节点，但不要求装配 message parts。
     *
     * <p>该兼容能力用于只需要完整节点集合、但不需要 assistant parts 的内部流程。
     * /messages 分页版本摘要使用按页候选查询；完整 tree 展示继续使用
     * {@link #findAllBySession(String, String, String)}。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @return 当前用户当前会话内全部 user/assistant 消息节点，按 nodeOrder 排序。
     */
    default List<ChatMessage> findAllMessageNodesBySession(String tenantId, String userId, String sessionId) {
        return findAllBySession(tenantId, userId, sessionId);
    }

    /**
     * 批量查询当前页消息对应的轻量版本候选。
     *
     * <p>实现只应读取同父同角色 sibling 及其预计算的切换 leaf，不得为分页接口加载整棵消息树。</p>
     */
    default List<ChatMessageVersionCandidate> findVersionCandidatesByMessageIds(
            String tenantId, String userId, String sessionId, List<String> messageIds) {
        return List.of();
    }

    /**
     * 按归属查询单条消息。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 消息标识。
     * @return 当前用户拥有的消息；不存在或不属于当前用户时为空。
     */
    Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId);

    /**
     * 按归属读取单条消息角色，不装配正文、Parts或附件。
     *
     * <p>默认实现兼容存量仓储；生产数据库仓储应覆盖该方法并使用轻量列投影。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param messageId 消息标识。
     * @return 当前用户拥有的消息角色；不存在或不属于当前用户时为空。
     */
    default Optional<String> findRoleByOwnerAndId(String tenantId, String userId, String messageId) {
        return findByOwnerAndId(tenantId, userId, messageId).map(ChatMessage::role);
    }

    /**
     * 按归属、会话和消息 ID 集合批量读取消息节点，不装配附件与 parts。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 消息 ID 集合。
     * @return 当前用户当前会话内命中的消息节点。
     */
    default List<ChatMessage> findByOwnerAndSessionAndIds(
            String tenantId, String userId, String sessionId, List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        return findAllMessageNodesBySession(tenantId, userId, sessionId).stream()
                .filter(message -> messageIds.contains(message.id()))
                .toList();
    }

    /**
     * 查询同一父节点下同角色的候选消息。
     */
    default List<ChatMessage> findSiblings(String tenantId, String userId, String sessionId,
                                           String parentMessageId, String role) {
        return List.of();
    }

    /**
     * 统计同父节点下同角色候选数量，用于生成 siblingIndex。
     */
    default int countSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return 0;
    }

    /**
     * 查询 root 到指定消息的完整路径。
     */
    default List<ChatMessage> findPathToMessage(String tenantId, String userId, String sessionId, String leafMessageId) {
        return pageMessages(new ChatMessagePageQuery(tenantId, userId, sessionId, leafMessageId, null,
                Integer.MAX_VALUE)).items();
    }

    /**
     * 查询 root 到指定消息的节点路径，不装配附件与 parts。
     *
     * <p>多消息分享只使用该路径验证分支归属，避免读取未选中消息的大量展示数据。</p>
     */
    default List<ChatMessage> findPathNodesToMessage(
            String tenantId, String userId, String sessionId, String leafMessageId) {
        return findPathToMessage(tenantId, userId, sessionId, leafMessageId);
    }

    /**
     * 保存消息附件引用。
     */
    default ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
        return attachment;
    }

    /**
     * 保存一条 assistant 历史消息结构化 part。
     *
     * @param part 已补齐归属字段的 message part。
     * @return 已保存的 part。
     */
    default ChatMessagePart savePart(ChatMessagePart part) {
        return part;
    }

    /**
     * 查询指定消息的附件引用。
     */
    default List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
        return List.of();
    }

    /**
     * 批量查询消息附件引用。
     *
     * <p>历史消息、tree 和 variants 装配附件时使用该方法，避免按消息逐条查询导致 N+1。</p>
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 消息 ID 列表。
     * @return 当前用户当前会话内的附件引用快照。
     */
    default List<ChatMessageAttachment> findAttachmentsByMessageIds(String tenantId, String userId, String sessionId,
                                                                    List<String> messageIds) {
        return List.of();
    }

    /**
     * 批量查询消息结构化 parts。
     *
     * @param tenantId 租户标识。
     * @param userId 用户标识。
     * @param sessionId 会话标识。
     * @param messageIds 消息 ID 列表。
     * @return 当前用户当前会话内的 message parts。
     */
    default List<ChatMessagePart> findPartsByMessageIds(String tenantId, String userId, String sessionId,
                                                        List<String> messageIds) {
        return List.of();
    }

    /**
     * 按会话读取最近消息的兼容方法。
     *
     * @param sessionId 前端聊天会话标识。
     * @param limit 最大返回条数。
     * @return 最近消息列表。
     */
    default List<ChatMessage> findRecentMessages(String sessionId, int limit) {
        return findRecentMessages(null, null, sessionId, limit);
    }
}
