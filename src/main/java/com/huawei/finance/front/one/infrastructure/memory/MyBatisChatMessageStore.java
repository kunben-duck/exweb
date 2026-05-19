package com.huawei.finance.front.one.infrastructure.memory;

import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessageAttachment;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 基于 MyBatis 的短期记忆数据库存储。
 *
 * <p>PostgreSQL 是短期记忆最终事实源；Redis 过期、失效或重启后，都从这里恢复会话消息。</p>
 */
@Repository
public class MyBatisChatMessageStore {
    private final ChatMessageMapper mapper;

    public MyBatisChatMessageStore(ChatMessageMapper mapper) {
        this.mapper = mapper;
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
        return message;
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
        return new ChatMessagePage(pageItemsAscending, null);
    }

    public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return mapper.findByOwnerAndId(tenantId, userId, messageId).map(this::toDomain);
    }

    public List<ChatMessage> findSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return mapper.findSiblings(tenantId, userId, sessionId, parentMessageId, role).stream()
                .map(this::toDomain)
                .toList();
    }

    public int countSiblings(String tenantId, String userId, String sessionId, String parentMessageId, String role) {
        return mapper.countSiblings(tenantId, userId, sessionId, parentMessageId, role);
    }

    public List<ChatMessage> findPathToMessage(String tenantId, String userId, String sessionId, String leafMessageId) {
        return mapper.findActivePath(tenantId, userId, sessionId, leafMessageId).stream()
                .map(this::toDomain)
                .toList();
    }

    public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
        return mapper.findAttachmentsByMessage(tenantId, userId, messageId).stream()
                .map(this::toAttachmentDomain)
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

}
