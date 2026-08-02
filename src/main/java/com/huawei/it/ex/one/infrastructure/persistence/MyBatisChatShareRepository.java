package com.huawei.it.ex.one.infrastructure.persistence;

import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatSharePage;
import com.huawei.it.ex.one.domain.chat.ChatShareSnapshot;
import com.huawei.it.ex.one.domain.chat.ChatShareSummary;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 聊天消息分享数据库仓储实现。
 */
@Repository
public class MyBatisChatShareRepository implements ChatShareRepository {
    private final ChatShareMapper mapper;
    private final ObjectMapper objectMapper;

    public MyBatisChatShareRepository(ChatShareMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatShare save(ChatShare share) {
        ChatShareRow row = toRow(share);
        int updated = mapper.update(row);
        if (updated == 0) {
            try {
                mapper.insert(row);
            } catch (DuplicateKeyException ex) {
                mapper.update(row);
            }
        }
        return share;
    }

    @Override
    public Optional<ChatShare> findById(String shareId) {
        if (shareId == null || shareId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findById(shareId)).map(this::toDomain);
    }

    @Override
    public ChatSharePage pageByOwner(String tenantId, String ownerUserId, int curPage, int pageSize) {
        int normalizedPage = Math.max(1, curPage);
        int normalizedSize = Math.max(1, Math.min(pageSize <= 0 ? 20 : pageSize, 100));
        long totalRows = mapper.countByOwner(tenantId, ownerUserId);
        long totalPages = totalRows == 0 ? 0 : (totalRows + normalizedSize - 1) / normalizedSize;
        long offset = (long) (normalizedPage - 1) * normalizedSize;
        List<ChatShareSummary> items = totalRows == 0 || offset >= totalRows
                ? List.of()
                : mapper.findPageByOwner(tenantId, ownerUserId, normalizedSize, offset).stream()
                .map(this::toSummary)
                .toList();
        return new ChatSharePage(items, normalizedPage, normalizedSize, totalRows, totalPages);
    }

    @Override
    public void revokeActiveBySession(String tenantId, String ownerUserId, String sessionId, Instant revokedAt) {
        Instant timestamp = revokedAt == null ? Instant.now() : revokedAt;
        mapper.revokeActiveBySession(tenantId, ownerUserId, sessionId, timestamp, timestamp);
    }

    private ChatShareRow toRow(ChatShare share) {
        ChatShareRow row = new ChatShareRow();
        row.setId(share.id());
        row.setTenantId(share.tenantId());
        row.setOwnerUserId(share.ownerUserId());
        row.setSourceSessionId(share.sourceSessionId());
        row.setSourceUserMessageId(share.sourceUserMessageId());
        row.setSourceAssistantMessageId(share.sourceAssistantMessageId());
        row.setSourceRunId(share.sourceRunId());
        row.setTitle(share.title());
        row.setScope(share.scope());
        row.setVisibility(share.visibility());
        row.setStatus(share.status());
        row.setExpiresAt(share.expiresAt());
        row.setRevokedAt(share.revokedAt());
        row.setSnapshotJson(toJson(share.snapshot()));
        row.setCreatedAt(share.createdAt());
        row.setUpdatedAt(share.updatedAt());
        return row;
    }

    private ChatShare toDomain(ChatShareRow row) {
        return new ChatShare(
                row.getId(),
                row.getTenantId(),
                row.getOwnerUserId(),
                row.getSourceSessionId(),
                row.getSourceUserMessageId(),
                row.getSourceAssistantMessageId(),
                row.getSourceRunId(),
                row.getTitle(),
                row.getScope(),
                row.getVisibility(),
                row.getStatus(),
                row.getExpiresAt(),
                row.getRevokedAt(),
                fromJson(row.getSnapshotJson()),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private ChatShareSummary toSummary(ChatShareRow row) {
        return new ChatShareSummary(
                row.getId(),
                row.getTitle(),
                row.getScope(),
                row.getVisibility(),
                row.getStatus(),
                row.getExpiresAt(),
                row.getSourceSessionId(),
                row.getSourceUserMessageId(),
                row.getSourceAssistantMessageId(),
                row.getSourceRunId(),
                row.getCreatedAt(),
                row.getUpdatedAt()
        );
    }

    private String toJson(ChatShareSnapshot snapshot) {
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("ChatShare snapshot 序列化失败", ex);
        }
    }

    private ChatShareSnapshot fromJson(String value) {
        try {
            return objectMapper.readValue(value, ChatShareSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("ChatShare snapshot 反序列化失败", ex);
        }
    }
}
