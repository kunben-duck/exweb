package com.huawei.finance.front.one.infrastructure.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePart;
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
 * <p>openGauss 是完整历史消息和消息 parts 的事实源；Redis 只缓存近期上下文，
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
        mapper.insert(
                message.id(),
                message.tenantId(),
                message.userId(),
                message.sessionId(),
                message.parentMessageId(),
                message.nodeOrder(),
                message.treeDepth(),
                message.siblingIndex(),
                message.role(),
                message.content(),
                message.tokenCount(),
                message.runId(),
                message.originType(),
                message.locked(),
                message.sourceSessionId(),
                message.sourceMessageId(),
                message.editedFromMessageId(),
                message.regeneratedFromMessageId(),
                message.metadataJson(),
                message.createdAt()
        );
        if (message.parts() != null) {
            message.parts().forEach(this::savePart);
        }
        return message;
    }

    public ChatMessagePart savePart(ChatMessagePart part) {
        mapper.insertPart(
                part.id(),
                part.tenantId(),
                part.userId(),
                part.sessionId(),
                part.messageId(),
                part.runId(),
                part.partType(),
                part.sourceType(),
                part.contentText(),
                part.title(),
                part.status(),
                part.channel(),
                part.displayHint(),
                part.visible(),
                toJson(part.payload()),
                part.partOrder(),
                part.createdAt()
        );
        return part;
    }

    public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
        mapper.insertAttachment(
                attachment.id(),
                attachment.tenantId(),
                attachment.userId(),
                attachment.sessionId(),
                attachment.messageId(),
                attachment.documentId(),
                attachment.attachmentOrder(),
                attachment.name(),
                attachment.contentType(),
                attachment.sizeBytes(),
                attachment.sourceAttachmentId(),
                attachment.createdAt()
        );
        return attachment;
    }

    public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
        if (sessionId == null || limit <= 0) {
            return List.of();
        }
        if (tenantId == null || userId == null) {
            return List.of();
        }
        // SQL 先从 leaf 向 root 取最近 N 条，返回给 Runtime/SubAgent 前再恢复为上下文阅读顺序。
        return mapper.findRecentActivePath(tenantId, userId, sessionId, null, limit).stream()
                .map(this::toDomain)
                .sorted(Comparator.comparing(ChatMessage::treeDepth).thenComparing(ChatMessage::nodeOrder))
                .toList();
    }

    public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
        return pageMessages(tenantId, userId, sessionId, null, cursor, limit);
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

    public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String leafMessageId, String cursor, int limit) {
        if (sessionId == null) {
            return new ChatMessagePage(List.of(), null);
        }
        int pageSize = Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
        List<ChatMessage> rows = mapper.findActivePath(tenantId, userId, sessionId, leafMessageId).stream()
                .map(this::toDomain)
                .toList();
        // active path 是一条有限可见路径，首版不再按 created_at 翻页；limit 用于保护极长历史。
        List<ChatMessage> pageItems = rows.size() > pageSize ? rows.subList(Math.max(0, rows.size() - pageSize), rows.size()) : rows;
        List<ChatMessage> pageItemsAscending = pageItems.stream()
                .sorted(Comparator.comparing(ChatMessage::treeDepth).thenComparing(ChatMessage::nodeOrder))
                .toList();
        return new ChatMessagePage(attachParts(tenantId, userId, sessionId, pageItemsAscending), null);
    }

    public List<ChatMessage> findAllBySession(String tenantId, String userId, String sessionId) {
        if (tenantId == null || userId == null || sessionId == null) {
            return List.of();
        }
        List<ChatMessage> messages = mapper.findAllBySession(tenantId, userId, sessionId).stream()
                .map(this::toDomain)
                .toList();
        return attachParts(tenantId, userId, sessionId, messages);
    }

    public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return mapper.findByOwnerAndId(tenantId, userId, messageId)
                .map(this::toDomain)
                .map(message -> attachParts(tenantId, userId, message.sessionId(), List.of(message)).getFirst());
    }

    public List<ChatMessage> findSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        List<ChatMessage> messages = mapper.findSiblings(tenantId, userId, sessionId, parentMessageId, role).stream()
                .map(this::toDomain)
                .toList();
        return attachParts(tenantId, userId, sessionId, messages);
    }

    public int countSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return mapper.countSiblings(tenantId, userId, sessionId, parentMessageId, role);
    }

    public List<ChatMessage> findPathToMessage(String tenantId, String userId, String sessionId, String leafMessageId) {
        List<ChatMessage> messages = mapper.findActivePath(tenantId, userId, sessionId, leafMessageId).stream()
                .map(this::toDomain)
                .toList();
        return attachParts(tenantId, userId, sessionId, messages);
    }

    public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
        return mapper.findAttachmentsByMessage(tenantId, userId, messageId).stream()
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

    private List<ChatMessage> attachParts(String tenantId, String userId, String sessionId, List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<String> messageIds = messages.stream().map(ChatMessage::id).distinct().toList();
        Map<String, List<ChatMessagePart>> partsByMessage = findPartsByMessageIds(tenantId, userId, sessionId, messageIds)
                .stream()
                .collect(Collectors.groupingBy(ChatMessagePart::messageId));
        return messages.stream()
                .map(message -> message.withParts(partsByMessage.getOrDefault(message.id(), List.of())))
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
