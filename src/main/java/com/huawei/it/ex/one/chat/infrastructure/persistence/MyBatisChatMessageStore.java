package com.huawei.it.ex.one.chat.infrastructure.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.chat.application.model.ChatMessagePageQuery;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessageAttachment;
import com.huawei.it.ex.one.chat.domain.ChatMessagePage;
import com.huawei.it.ex.one.chat.domain.ChatMessagePart;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;

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

    public MyBatisChatMessageStore(ChatMessageMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public ChatMessage save(ChatMessage message) {
        mapper.insert(toRow(message));
        if (message.parts() != null) {
            message.parts().forEach(this::savePart);
        }
        return message;
    }

    public ChatMessage updateAssistantMessage(ChatMessage message) {
        int updated = mapper.updateAssistant(toRow(message));
        if (updated != 1) {
            throw new IllegalArgumentException("assistant 消息不存在或不属于当前用户: " + message.id());
        }
        if (message.parts() != null) {
            message.parts().forEach(this::savePart);
        }
        return message;
    }

    public ChatMessagePart savePart(ChatMessagePart part) {
        mapper.insertPart(toRow(part));
        return part;
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

    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        if (tenantId == null || userId == null) {
            return List.of();
        }
        // SQL 先从 leaf 向 root 取最近 N 条，返回给 Runtime/DomainAgent 前再恢复为上下文阅读顺序。
        return mapper.findRecentActivePath(tenantId, userId, sessionId, null, limit).stream()
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
        List<ChatMessage> rows = mapper.findActivePath(query.tenantId(), query.userId(), query.sessionId(),
                        query.leafMessageId()).stream()
                .map(this::toDomain)
                .toList();
        // active path 是一条有限可见路径，首版不再按 created_at 翻页；limit 用于保护极长历史。
        List<ChatMessage> pageItems = rows.size() > pageSize ? rows.subList(Math.max(0, rows.size() - pageSize), rows.size()) : rows;
        List<ChatMessage> pageItemsAscending = pageItems.stream()
                .sorted(Comparator.comparing(ChatMessage::treeDepth).thenComparing(ChatMessage::nodeOrder))
                .toList();
        return new ChatMessagePage(attachMessageChildren(query.tenantId(), query.userId(), query.sessionId(), pageItemsAscending),
                null);
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

    public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return mapper.findByOwnerAndId(tenantId, userId, messageId)
                .map(this::toDomain)
                .map(message -> attachMessageChildren(tenantId, userId, message.sessionId(), List.of(message)).getFirst());
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
}
