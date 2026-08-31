/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import com.huawei.it.ex.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessageVersionCandidate;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import java.sql.SQLTimeoutException;

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
    private static final AppLogger log = AppLoggerFactory.getLogger(LayeredChatMessageRepository.class);

    private final RedisShortTermMemoryCache redisCache;
    private final MyBatisChatMessageStore databaseStore;
    private final ShortTermMemoryStorageProperties properties;
    private volatile Instant databaseRetryAfter = Instant.MIN;
    private volatile Instant memoryReadRetryAfter = Instant.MIN;

    public LayeredChatMessageRepository(RedisShortTermMemoryCache redisCache, MyBatisChatMessageStore databaseStore,
                                        ShortTermMemoryStorageProperties properties) {
        this.redisCache = redisCache;
        this.databaseStore = databaseStore;
        this.properties = properties;
    }

    @Override
    public ChatMessage save(ChatMessage message) {
        if (!canUseDatabase()) {
            updateCacheBestEffort(() -> redisCache.append(message));
            return message;
        }
        try {
            ChatMessage saved = databaseStore.save(message);
            updateCacheAfterCommit(() -> redisCache.append(saved));
            return saved;
        } catch (RuntimeException ex) {
            // 默认要求数据库写成功，确保数据库是消息事实源；本地联调可显式关闭 databaseRequired。
            if (properties.isDatabaseRequired()) {
                throw ex;
            }
            markDatabaseFailure(ex);
            updateCacheBestEffort(() -> redisCache.append(message));
            return message;
        }
    }

    @Override
    public ChatMessage updateAssistantMessage(ChatMessage message) {
        ChatMessage previous = databaseStore.findByOwnerAndId(message.tenantId(), message.userId(), message.id())
                .orElse(null);
        ChatMessage updated = databaseStore.updateAssistantMessage(message);
        ChatMessage cacheValue = mergeParts(previous, updated);
        updateCacheAfterCommit(() -> {
            if (previous != null) {
                redisCache.remove(previous);
            }
            redisCache.append(cacheValue);
        });
        return cacheValue;
    }

    @Override
    public ChatMessage updateAssistantMetadata(ChatMessage existing, String metadataJson) {
        ChatMessage updated = databaseStore.updateAssistantMetadata(existing, metadataJson);
        updateCacheAfterCommit(() -> {
            redisCache.remove(existing);
            redisCache.append(updated);
        });
        return updated;
    }

    @Override
    public ChatMessage updateAssistantAsyncResult(
            ChatMessage existing,
            ChatMessage update,
            boolean replaceCurrentRunParts) {
        ChatMessage persisted = databaseStore.updateAssistantAsyncResult(
                update, replaceCurrentRunParts);
        ChatMessage cacheValue = mergeAsyncResultParts(
                existing, persisted, replaceCurrentRunParts);
        updateCacheAfterCommit(() -> {
            redisCache.remove(existing);
            redisCache.append(cacheValue);
        });
        return cacheValue;
    }

    private ChatMessage mergeAsyncResultParts(
            ChatMessage existing,
            ChatMessage update,
            boolean replaceCurrentRunParts) {
        List<ChatMessagePart> merged = new java.util.ArrayList<>();
        for (ChatMessagePart part : existing.parts()) {
            if (!replaceCurrentRunParts || !java.util.Objects.equals(update.runId(), part.runId())) {
                merged.add(part);
            }
        }
        merged.addAll(update.parts());
        return update.withParts(List.copyOf(merged));
    }

    private ChatMessage mergeParts(ChatMessage previous, ChatMessage updated) {
        if (previous == null || previous.parts() == null || previous.parts().isEmpty()) {
            return updated;
        }
        java.util.Map<String, com.huawei.it.ex.one.domain.chat.ChatMessagePart> merged =
                new java.util.LinkedHashMap<>();
        previous.parts().forEach(part -> merged.put(part.id(), part));
        if (updated.parts() != null) {
            updated.parts().forEach(part -> merged.put(part.id(), part));
        }
        return updated.withParts(java.util.List.copyOf(merged.values()));
    }

    @Override
    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        return findRecentMessages(tenantId, userId, sessionId, null, limit);
    }

    @Override
    public List<ChatMessage> findRecentMessages(
            String tenantId, String userId, String sessionId, String leafMessageId, int limit) {
        List<ChatMessage> cached = redisCache.findRecentMessages(
                tenantId, userId, sessionId, leafMessageId, limit);
        if (!cached.isEmpty()) {
            return cached;
        }
        if (!canReadMemoryFromDatabase()) {
            return List.of();
        }
        List<ChatMessage> persisted;
        try {
            persisted = databaseStore.findRecentMessages(
                    tenantId, userId, sessionId, leafMessageId, limit);
        } catch (RuntimeException ex) {
            // 记忆是可选增强；数据库回源失败时必须放行主流程，写入严格性仍由 databaseRequired 控制。
            markMemoryReadFailure(sessionId, ex);
            return List.of();
        }
        // 数据库成功回源后始终重建缓存；空路径也会删除不连续或已切换分支的旧 key。
        redisCache.replaceSessionMessages(tenantId, userId, sessionId, persisted);
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
    public List<ChatMessageVersionCandidate> findVersionCandidatesByMessageIds(
            String tenantId, String userId, String sessionId, List<String> messageIds) {
        return databaseStore.findVersionCandidatesByMessageIds(tenantId, userId, sessionId, messageIds);
    }

    @Override
    public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
        return databaseStore.findByOwnerAndId(tenantId, userId, messageId);
    }

    @Override
    public Optional<String> findRoleByOwnerAndId(String tenantId, String userId, String messageId) {
        return databaseStore.findRoleByOwnerAndId(tenantId, userId, messageId);
    }

    @Override
    public List<ChatMessage> findByOwnerAndSessionAndIds(
            String tenantId, String userId, String sessionId, List<String> messageIds) {
        return databaseStore.findByOwnerAndSessionAndIds(tenantId, userId, sessionId, messageIds);
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
    public List<ChatMessage> findPathNodesToMessage(
            String tenantId, String userId, String sessionId, String leafMessageId) {
        return databaseStore.findPathNodesToMessage(tenantId, userId, sessionId, leafMessageId);
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

    private boolean canReadMemoryFromDatabase() {
        return !Instant.now().isBefore(memoryReadRetryAfter);
    }

    private void markDatabaseFailure(RuntimeException ex) {
        databaseRetryAfter = Instant.now().plus(properties.getDatabaseFailureBackoff());
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_UNAVAILABLE,
                        "Short-term message database is unavailable; using configured degradation")
                .operation("chat-message.database.access")
                .attribute("failureBackoff", properties.getDatabaseFailureBackoff())
                .build(), ex);
    }

    private void markMemoryReadFailure(String sessionId, RuntimeException ex) {
        memoryReadRetryAfter = Instant.now().plus(properties.getDatabaseFailureBackoff());
        boolean timeout = isQueryTimeout(ex);
        SystemErrorCode errorCode = timeout
                ? SystemErrorCode.DATABASE_QUERY_TIMEOUT
                : SystemErrorCode.DATABASE_READ_FAILED;
        String message = timeout
                ? "Short-term memory database query timed out; continuing without memory"
                : "Short-term memory database read failed; continuing without memory";
        log.warn(SystemErrorLogEntry.builder(errorCode, message)
                .sessionId(sessionId)
                .operation("short-term-memory.database.read")
                .attribute("failureBackoff", properties.getDatabaseFailureBackoff())
                .attribute("queryTimeoutSeconds", properties.getDatabaseQueryTimeoutSeconds())
                .attribute("failureType", ex.getClass().getName())
                .build());
    }

    private boolean isQueryTimeout(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof QueryTimeoutException
                    || current instanceof TransactionTimedOutException
                    || current instanceof SQLTimeoutException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private void updateCacheAfterCommit(Runnable cacheUpdate) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) {
            updateCacheBestEffort(cacheUpdate);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                updateCacheBestEffort(cacheUpdate);
            }
        });
    }

    private void updateCacheBestEffort(Runnable cacheUpdate) {
        try {
            cacheUpdate.run();
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_CACHE_SYNC_FAILED,
                            "Message database commit succeeded but Redis cache synchronization failed")
                    .operation("chat-message.cache.after-commit")
                    .build(), ex);
        }
    }
}
