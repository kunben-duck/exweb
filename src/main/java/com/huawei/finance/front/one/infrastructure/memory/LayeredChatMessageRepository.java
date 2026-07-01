package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

/**
 * 聊天历史消息组合仓储。
 *
 * <p>数据库是历史消息事实源；Redis 仅在短期记忆开启且缓存开启时作为最近消息热缓存。
 * 读取最近消息时优先查 Redis，Redis 为空或过期时回源数据库并预热缓存。</p>
 */
@Primary
@Repository
@EnableConfigurationProperties(ShortTermMemoryStorageProperties.class)
public class LayeredChatMessageRepository implements ChatMessageRepository {
    private static final Logger log = LoggerFactory.getLogger(LayeredChatMessageRepository.class);

    private final RedisShortTermMemoryCache redisCache;
    private final MyBatisChatMessageStore databaseStore;
    private final ShortTermMemoryStorageProperties properties;
    private volatile Instant databaseRetryAfter = Instant.MIN;

    public LayeredChatMessageRepository(RedisShortTermMemoryCache redisCache, MyBatisChatMessageStore databaseStore,
                                        ShortTermMemoryStorageProperties properties) {
        this.redisCache = redisCache;
        this.databaseStore = databaseStore;
        this.properties = properties;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        boolean cached = redisCache.append(message);
        if (!canUseDatabase()) {
            return message;
        }
        try {
            return databaseStore.save(message);
        } catch (RuntimeException ex) {
            // 默认要求数据库写成功，确保数据库是消息事实源；本地联调可显式关闭 databaseRequired。
            if (properties.isDatabaseRequired()) {
                if (cached) {
                    redisCache.remove(message);
                }
                throw ex;
            }
            markDatabaseFailure(ex);
            return message;
        }
    }

    @Override
    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        List<ChatMessage> cached = redisCache.findRecentMessages(tenantId, userId, sessionId, limit);
        if (!cached.isEmpty()) {
            return cached;
        }
        if (!canUseDatabase()) {
            return List.of();
        }
        List<ChatMessage> persisted;
        try {
            persisted = databaseStore.findRecentMessages(tenantId, userId, sessionId, limit);
        } catch (RuntimeException ex) {
            if (properties.isDatabaseRequired()) {
                throw ex;
            }
            markDatabaseFailure(ex);
            return List.of();
        }
        if (!persisted.isEmpty()) {
            redisCache.replaceSessionMessages(tenantId, userId, sessionId, persisted);
        }
        return persisted;
    }

    @Override
    public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
        // 历史消息分页必须以数据库为事实源；Redis 只缓存最近上下文，不适合作为翻页数据源。
        return databaseStore.pageMessages(tenantId, userId, sessionId, cursor, limit);
    }

    @Override
    public Map<String, ChatMessage> findFirstAssistantMessagesBySessionIds(
            String tenantId, String userId, List<String> sessionIds) {
        // 会话列表摘要必须批量回源数据库；Redis 最近消息缓存无法保证包含“第一条回答”。
        return databaseStore.findFirstAssistantMessagesBySessionIds(tenantId, userId, sessionIds);
    }

    @Override
    public ChatMessagePage pageMessages(ChatMessagePageQuery query) {
        return databaseStore.pageMessages(query);
    }

    @Override
    public List<ChatMessage> findAllBySession(String tenantId, String userId, String sessionId) {
        return databaseStore.findAllBySession(tenantId, userId, sessionId);
    }

    @Override
    public List<ChatMessage> findAllMessageNodesBySession(String tenantId, String userId, String sessionId) {
        return databaseStore.findAllMessageNodesBySession(tenantId, userId, sessionId);
    }

    @Override
    public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
        return databaseStore.findByOwnerAndId(tenantId, userId, messageId);
    }

    @Override
    public List<ChatMessage> findSiblings(String tenantId, String userId, String sessionId,
                                          String parentMessageId, String role) {
        return databaseStore.findSiblings(tenantId, userId, sessionId, parentMessageId, role);
    }

    @Override
    public int countSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return databaseStore.countSiblings(tenantId, userId, sessionId, parentMessageId, role);
    }

    @Override
    public List<ChatMessage> findPathToMessage(String tenantId, String userId, String sessionId, String leafMessageId) {
        return databaseStore.findPathToMessage(tenantId, userId, sessionId, leafMessageId);
    }

    @Override
    public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
        return databaseStore.saveAttachment(attachment);
    }

    @Override
    public ChatMessagePart savePart(ChatMessagePart part) {
        return databaseStore.savePart(part);
    }

    @Override
    public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
        return databaseStore.findAttachments(tenantId, userId, messageId);
    }

    @Override
    public List<ChatMessageAttachment> findAttachmentsByMessageIds(String tenantId, String userId, String sessionId,
                                                                   List<String> messageIds) {
        return databaseStore.findAttachmentsByMessageIds(tenantId, userId, sessionId, messageIds);
    }

    @Override
    public List<ChatMessagePart> findPartsByMessageIds(String tenantId, String userId, String sessionId,
                                                       List<String> messageIds) {
        return databaseStore.findPartsByMessageIds(tenantId, userId, sessionId, messageIds);
    }

    private boolean canUseDatabase() {
        return properties.isDatabaseRequired() || !Instant.now().isBefore(databaseRetryAfter);
    }

    private void markDatabaseFailure(RuntimeException ex) {
        databaseRetryAfter = Instant.now().plus(properties.getDatabaseFailureBackoff());
        log.warn("短期消息数据库暂不可用，{} 后重试；本次请求降级处理。原因：{}",
                properties.getDatabaseFailureBackoff(), ex.getMessage());
    }
}
