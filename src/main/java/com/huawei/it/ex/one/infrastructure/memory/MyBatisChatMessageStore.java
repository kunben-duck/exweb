/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.memory;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.integration.memory.ChatMessagePageQuery;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessageVersionCandidate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 基于 MyBatis 的聊天消息数据库存储。
 *
 * <p>数据库是完整历史消息和消息 parts 的事实源；Redis 只缓存近期上下文，
 * 失效或重启后都从这里恢复会话消息。</p>
 */
@Repository
public class MyBatisChatMessageStore {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ChatMessageMapper mapper;
    private final ObjectMapper objectMapper;
    private final int partBatchMaxSize;
    private final long partBatchMaxBytes;
    private final ChatMessagePageCursorCodec messagePageCursorCodec = new ChatMessagePageCursorCodec();

    public MyBatisChatMessageStore(ChatMessageMapper mapper, ObjectMapper objectMapper,
                                   ChatStreamProperties chatStreamProperties) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.partBatchMaxSize = chatStreamProperties.requiredAssistantPartBatchMaxSize();
        this.partBatchMaxBytes = chatStreamProperties.requiredAssistantPartBatchMaxBytes();
    }

    public ChatMessage save(ChatMessage message) {
        mapper.insert(toRow(message));
        saveParts(message.parts());
        return message;
    }

    public ChatMessage updateAssistantMessage(ChatMessage message) {
        int updated = mapper.updateAssistant(toRow(message));
        if (updated != 1) {
            throw new IllegalArgumentException("assistant 消息不存在或不属于当前用户: " + message.id());
        }
        saveParts(message.parts());
        return message;
    }

    public ChatMessage updateAssistantMetadata(ChatMessage existing, String metadataJson) {
        ChatMessage updatedMessage = existing.withMetadataJson(metadataJson);
        int updated = mapper.updateAssistantMetadata(toRow(updatedMessage));
        if (updated != 1) {
            throw new IllegalArgumentException("assistant 消息不存在或不属于当前用户: " + existing.id());
        }
        return updatedMessage;
    }

    public ChatMessage updateAssistantAsyncResult(
            ChatMessage update,
            boolean replaceCurrentRunParts) {
        if (replaceCurrentRunParts) {
            mapper.deletePartsByMessageAndRun(
                    update.tenantId(), update.userId(), update.sessionId(), update.id(), update.runId());
        }
        int updated = mapper.updateAssistant(toRow(update));
        if (updated != 1) {
            throw new IllegalArgumentException("assistant 消息不存在或不属于当前用户: " + update.id());
        }
        saveParts(update.parts());
        return update;
    }

    public ChatMessagePart savePart(ChatMessagePart part) {
        insertPartBatch(List.of(toRow(part)));
        return part;
    }

    private void saveParts(List<ChatMessagePart> parts) {
        if (parts == null || parts.isEmpty()) {
            return;
        }
        List<ChatMessagePartRow> batch = new ArrayList<>(Math.min(partBatchMaxSize, parts.size()));
        long batchBytes = 0L;
        for (ChatMessagePart part : parts) {
            ChatMessagePartRow row = toRow(part);
            long rowBytes = estimatedUtf8Bytes(row);
            if (!batch.isEmpty() && (batch.size() >= partBatchMaxSize
                    || saturatedAdd(batchBytes, rowBytes) > partBatchMaxBytes)) {
                insertPartBatch(batch);
                batch = new ArrayList<>(Math.min(partBatchMaxSize, parts.size()));
                batchBytes = 0L;
            }
            batch.add(row);
            batchBytes = saturatedAdd(batchBytes, rowBytes);
            if (batch.size() >= partBatchMaxSize || batchBytes >= partBatchMaxBytes) {
                insertPartBatch(batch);
                batch = new ArrayList<>(Math.min(partBatchMaxSize, parts.size()));
                batchBytes = 0L;
            }
        }
        insertPartBatch(batch);
    }

    private void insertPartBatch(List<ChatMessagePartRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<ChatMessagePartRow> immutableRows = List.copyOf(rows);
        int inserted = mapper.insertParts(immutableRows);
        if (inserted != immutableRows.size()) {
            throw new IllegalStateException("assistant parts 批量写入结果数量不一致: expected="
                    + immutableRows.size() + ", actual=" + inserted);
        }
    }

    private long estimatedUtf8Bytes(ChatMessagePartRow row) {
        long bytes = Integer.BYTES + 1L;
        bytes = saturatedAdd(bytes, utf8Bytes(row.getId()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getTenantId()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getUserId()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getSessionId()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getMessageId()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getRunId()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getPartType()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getSourceType()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getContentText()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getTitle()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getStatus()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getChannel()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getDisplayHint()));
        bytes = saturatedAdd(bytes, utf8Bytes(row.getPayloadJson()));
        return saturatedAdd(bytes, row.getCreatedAt() == null ? 0L : utf8Bytes(row.getCreatedAt().toString()));
    }

    private long utf8Bytes(String value) {
        return value == null ? 0L : value.getBytes(StandardCharsets.UTF_8).length;
    }

    private long saturatedAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
        mapper.insertAttachment(toRow(attachment));
        return attachment;
    }

    private ChatMessageRow toRow(ChatMessage message) {
        ChatMessageRow row = new ChatMessageRow();
        row.setId(message.id());
        row.setTenantId(message.tenantId());
        row.setUserId(message.userId());
        row.setSessionId(message.sessionId());
        row.setParentMessageId(message.parentMessageId());
        row.setNodeOrder(message.nodeOrder());
        row.setTreeDepth(message.treeDepth());
        row.setSiblingIndex(message.siblingIndex());
        row.setRole(message.role());
        row.setContent(message.content());
        row.setTokenCount(message.tokenCount());
        row.setRunId(message.runId());
        row.setOriginType(message.originType());
        row.setLocked(message.locked());
        row.setSourceSessionId(message.sourceSessionId());
        row.setSourceMessageId(message.sourceMessageId());
        row.setEditedFromMessageId(message.editedFromMessageId());
        row.setRegeneratedFromMessageId(message.regeneratedFromMessageId());
        row.setMetadataJson(message.metadataJson());
        row.setCreatedAt(message.createdAt());
        return row;
    }

    private ChatMessagePartRow toRow(ChatMessagePart part) {
        ChatMessagePartRow row = new ChatMessagePartRow();
        row.setId(part.id());
        row.setTenantId(part.tenantId());
        row.setUserId(part.userId());
        row.setSessionId(part.sessionId());
        row.setMessageId(part.messageId());
        row.setRunId(part.runId());
        row.setPartType(part.partType());
        row.setSourceType(part.sourceType());
        row.setContentText(part.contentText());
        row.setTitle(part.title());
        row.setStatus(part.status());
        row.setChannel(part.channel());
        row.setDisplayHint(part.displayHint());
        row.setVisible(part.visible());
        row.setPayloadJson(toJson(part.payload()));
        row.setPartOrder(part.partOrder());
        row.setCreatedAt(part.createdAt());
        return row;
    }

    private ChatMessageAttachmentRow toRow(ChatMessageAttachment attachment) {
        ChatMessageAttachmentRow row = new ChatMessageAttachmentRow();
        row.setId(attachment.id());
        row.setTenantId(attachment.tenantId());
        row.setUserId(attachment.userId());
        row.setSessionId(attachment.sessionId());
        row.setMessageId(attachment.messageId());
        row.setDocumentId(attachment.documentId());
        row.setAttachmentOrder(attachment.attachmentOrder());
        row.setName(attachment.name());
        row.setContentType(attachment.contentType());
        row.setSizeBytes(attachment.sizeBytes());
        row.setSourceAttachmentId(attachment.sourceAttachmentId());
        row.setCreatedAt(attachment.createdAt());
        return row;
    }

    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.memory.short-term.storage.database-query-timeout-seconds:2}"
    )
    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        return findRecentMessages(tenantId, userId, sessionId, null, limit);
    }

    @Transactional(
            readOnly = true,
            timeoutString = "${financeex.memory.short-term.storage.database-query-timeout-seconds:2}"
    )
    public List<ChatMessage> findRecentMessages(
            String tenantId, String userId, String sessionId, String leafMessageId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        if (tenantId == null || userId == null) {
            return List.of();
        }
        // SQL 先从 leaf 向 root 取最近 N 条，返回给 Runtime/DomainAgent 前再恢复为上下文阅读顺序。
        return mapper.findRecentActivePath(tenantId, userId, sessionId, leafMessageId, limit).stream()
                .map(this::toDomain)
                .sorted(Comparator.comparing(ChatMessage::treeDepth).thenComparing(ChatMessage::nodeOrder))
                .toList();
    }

    public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
        return pageMessages(new ChatMessagePageQuery(tenantId, userId, sessionId, null, cursor, limit));
    }

    public Map<String, ChatMessage> findFirstAssistantMessagesBySessionIds(
            String tenantId, String userId, List<String> sessionIds) {
        if (tenantId == null || userId == null || sessionIds == null || sessionIds.isEmpty()) {
            return Map.of();
        }
        return mapper.findFirstAssistantBySessions(tenantId, userId, sessionIds).stream()
                .map(this::toDomain)
                .collect(Collectors.toMap(ChatMessage::sessionId, Function.identity(), (first, ignored) -> first));
    }

    public ChatMessagePage pageMessages(ChatMessagePageQuery query) {
        if (query.sessionId() == null) {
            return new ChatMessagePage(List.of(), null);
        }
        int pageSize = Math.max(1, Math.min(query.limit() <= 0 ? 50 : query.limit(), 200));
        String requestedLeafMessageId = normalizeText(query.leafMessageId());
        String pageStartMessageId = null;
        String anchorLeafMessageId = requestedLeafMessageId;
        boolean cursorRequest = query.cursor() != null && !query.cursor().isBlank();
        if (cursorRequest) {
            ChatMessagePageCursorCodec.Cursor cursor = messagePageCursorCodec.decode(query.cursor());
            if (!query.sessionId().equals(cursor.sessionId())) {
                throw new IllegalArgumentException("消息分页游标不属于当前会话");
            }
            if (requestedLeafMessageId != null
                    && !requestedLeafMessageId.equals(cursor.anchorLeafMessageId())) {
                throw new IllegalArgumentException("leafMessageId 与消息分页游标不匹配");
            }
            anchorLeafMessageId = cursor.anchorLeafMessageId();
            pageStartMessageId = cursor.pageStartMessageId();
        }

        List<ChatMessageRow> rows = mapper.findActivePathPage(
                query.tenantId(), query.userId(), query.sessionId(), pageStartMessageId,
                cursorRequest ? null : requestedLeafMessageId, pageSize + 1);
        if (cursorRequest && rows.isEmpty()) {
            throw new IllegalArgumentException("消息分页游标指向的消息不存在或不属于当前路径");
        }
        if (rows.isEmpty()) {
            return new ChatMessagePage(List.of(), null);
        }
        if (anchorLeafMessageId == null) {
            anchorLeafMessageId = rows.getFirst().getId();
        }

        boolean hasMore = rows.size() > pageSize;
        List<ChatMessage> pageItemsAscending = rows.subList(0, Math.min(pageSize, rows.size())).stream()
                .map(this::toDomain)
                .sorted(Comparator.comparing(ChatMessage::treeDepth).thenComparing(ChatMessage::nodeOrder))
                .toList();
        String nextCursor = hasMore
                ? messagePageCursorCodec.encode(query.sessionId(), anchorLeafMessageId, rows.get(pageSize).getId())
                : null;
        return new ChatMessagePage(attachMessageChildren(query.tenantId(), query.userId(), query.sessionId(), pageItemsAscending),
                nextCursor);
    }

    public List<ChatMessage> findAllBySession(String tenantId, String userId, String sessionId) {
        if (tenantId == null || userId == null || sessionId == null) {
            return List.of();
        }
        List<ChatMessage> messages = mapper.findAllBySession(tenantId, userId, sessionId).stream()
                .map(this::toDomain)
                .toList();
        return attachMessageChildren(tenantId, userId, sessionId, messages);
    }

    public List<ChatMessage> findAllMessageNodesBySession(String tenantId, String userId, String sessionId) {
        if (tenantId == null || userId == null || sessionId == null) {
            return List.of();
        }
        return mapper.findAllBySession(tenantId, userId, sessionId).stream()
                .map(this::toDomain)
                .toList();
    }

    public List<ChatMessageVersionCandidate> findVersionCandidatesByMessageIds(
            String tenantId, String userId, String sessionId, List<String> messageIds) {
        if (tenantId == null || userId == null || sessionId == null || messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        List<String> normalizedIds = messageIds.stream()
                .map(this::normalizeText)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        return mapper.findVersionCandidatesByMessages(tenantId, userId, sessionId, normalizedIds).stream()
                .map(row -> new ChatMessageVersionCandidate(
                        row.getPageMessageId(),
                        row.getMessageId(),
                        row.getRole(),
                        row.getSiblingIndex() == null ? 0 : row.getSiblingIndex(),
                        Boolean.TRUE.equals(row.getLocked()),
                        row.getOriginType(),
                        row.getEditedFromMessageId(),
                        row.getRegeneratedFromMessageId(),
                        row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt(),
                        row.getSwitchLeafMessageId()
                ))
                .toList();
    }

    public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return mapper.findByOwnerAndId(tenantId, userId, messageId)
                .map(this::toDomain)
                .map(message -> attachMessageChildren(tenantId, userId, message.sessionId(), List.of(message)).getFirst());
    }

    public Optional<String> findRoleByOwnerAndId(String tenantId, String userId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return mapper.findRoleByOwnerAndId(tenantId, userId, messageId);
    }

    public List<ChatMessage> findByOwnerAndSessionAndIds(
            String tenantId, String userId, String sessionId, List<String> messageIds) {
        if (tenantId == null || userId == null || sessionId == null || messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        List<String> normalizedIds = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        return mapper.findByOwnerSessionAndIds(tenantId, userId, sessionId, normalizedIds).stream()
                .map(this::toDomain)
                .toList();
    }

    public List<ChatMessage> findSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        List<ChatMessage> messages = mapper.findSiblings(tenantId, userId, sessionId, parentMessageId, role).stream()
                .map(this::toDomain)
                .toList();
        return attachMessageChildren(tenantId, userId, sessionId, messages);
    }

    public int countSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return mapper.countSiblings(tenantId, userId, sessionId, parentMessageId, role);
    }

    public List<ChatMessage> findPathToMessage(String tenantId, String userId, String sessionId, String leafMessageId) {
        List<ChatMessage> messages = mapper.findActivePath(tenantId, userId, sessionId, leafMessageId).stream()
                .map(this::toDomain)
                .toList();
        return attachMessageChildren(tenantId, userId, sessionId, messages);
    }

    public List<ChatMessage> findPathNodesToMessage(
            String tenantId, String userId, String sessionId, String leafMessageId) {
        if (tenantId == null || userId == null || sessionId == null
                || leafMessageId == null || leafMessageId.isBlank()) {
            return List.of();
        }
        return mapper.findActivePath(tenantId, userId, sessionId, leafMessageId).stream()
                .map(this::toDomain)
                .toList();
    }

    public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
        return mapper.findAttachmentsByMessage(tenantId, userId, messageId).stream()
                .map(this::toAttachmentDomain)
                .toList();
    }

    public List<ChatMessageAttachment> findAttachmentsByMessageIds(String tenantId, String userId, String sessionId,
                                                                   List<String> messageIds) {
        if (tenantId == null || userId == null || sessionId == null || messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        List<String> normalizedIds = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        return mapper.findAttachmentsByMessages(tenantId, userId, sessionId, normalizedIds).stream()
                .map(this::toAttachmentDomain)
                .toList();
    }

    public List<ChatMessagePart> findPartsByMessageIds(String tenantId, String userId, String sessionId,
                                                       List<String> messageIds) {
        if (tenantId == null || userId == null || sessionId == null || messageIds == null || messageIds.isEmpty()) {
            return List.of();
        }
        List<String> normalizedIds = messageIds.stream()
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (normalizedIds.isEmpty()) {
            return List.of();
        }
        return mapper.findPartsByMessages(tenantId, userId, sessionId, normalizedIds).stream()
                .map(this::toPartDomain)
                .toList();
    }

    private List<ChatMessage> attachMessageChildren(String tenantId, String userId, String sessionId,
                                                    List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> messageIds = messages.stream().map(ChatMessage::id).distinct().toList();
        Map<String, List<ChatMessagePart>> partsByMessage = findPartsByMessageIds(tenantId, userId, sessionId, messageIds)
                .stream()
                .collect(Collectors.groupingBy(ChatMessagePart::messageId));
        Map<String, List<ChatMessageAttachment>> attachmentsByMessage =
                findAttachmentsByMessageIds(tenantId, userId, sessionId, messageIds)
                        .stream()
                        .collect(Collectors.groupingBy(ChatMessageAttachment::messageId));
        return messages.stream()
                .map(message -> message.withParts(partsByMessage.getOrDefault(message.id(), List.of())))
                .map(message -> message.withAttachments(attachmentsByMessage.getOrDefault(message.id(), List.of())))
                .toList();
    }

    private ChatMessage toDomain(ChatMessageRow row) {
        return new ChatMessage(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getParentMessageId(),
                row.getNodeOrder() == null ? 0L : row.getNodeOrder(),
                row.getTreeDepth(),
                row.getSiblingIndex(),
                row.getRole(),
                row.getContent(),
                row.getTokenCount(),
                row.getRunId(),
                row.getOriginType(),
                Boolean.TRUE.equals(row.getLocked()),
                row.getSourceSessionId(),
                row.getSourceMessageId(),
                row.getEditedFromMessageId(),
                row.getRegeneratedFromMessageId(),
                row.getMetadataJson(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt()
        );
    }

    private ChatMessageAttachment toAttachmentDomain(ChatMessageAttachmentRow row) {
        return new ChatMessageAttachment(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getMessageId(),
                row.getDocumentId(),
                row.getAttachmentOrder() == null ? 0 : row.getAttachmentOrder(),
                row.getName(),
                row.getContentType(),
                row.getSizeBytes(),
                row.getSourceAttachmentId(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt()
        );
    }

    private ChatMessagePart toPartDomain(ChatMessagePartRow row) {
        return new ChatMessagePart(
                row.getId(),
                row.getTenantId(),
                row.getUserId(),
                row.getSessionId(),
                row.getMessageId(),
                row.getRunId(),
                row.getPartType(),
                row.getSourceType(),
                row.getContentText(),
                row.getTitle(),
                row.getStatus(),
                row.getChannel(),
                row.getDisplayHint(),
                row.getVisible(),
                fromJson(row.getPayloadJson()),
                row.getPartOrder(),
                row.getCreatedAt() == null ? Instant.EPOCH : row.getCreatedAt()
        );
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("序列化消息 part payload 失败", ex);
        }
    }

    private Map<String, Object> fromJson(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(payloadJson, MAP_TYPE);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("反序列化消息 part payload 失败", ex);
        }
    }

    private String normalizeText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
