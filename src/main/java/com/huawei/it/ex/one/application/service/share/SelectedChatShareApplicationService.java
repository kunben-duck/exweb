package com.huawei.it.ex.one.application.service.share;

import com.huawei.it.ex.one.application.config.ChatShareSelectedMessagesProperties;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.share.ChatShareAccessPolicy;
import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.ChatShareAttachmentSnapshot;
import com.huawei.it.ex.one.domain.chat.ChatShareSelectedMessageSnapshot;
import com.huawei.it.ex.one.domain.chat.ChatShareSnapshot;
import com.huawei.it.ex.one.domain.chat.ChatShareSnapshotPart;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 多消息固定快照分享应用服务。
 *
 * <p>该入口与单轮 assistant 分享解耦，只读取服务端可信消息树并保存前端明确选中的节点。</p>
 */
@Service
public class SelectedChatShareApplicationService {
    private static final String STATUS_DELETED = "DELETED";
    private static final String ROLE_USER = "user";
    private static final String ROLE_ASSISTANT = "assistant";
    private static final int MAX_TITLE_LENGTH = 120;

    private final ChatShareRepository shareRepository;
    private final ChatMessageRepository messageRepository;
    private final SessionRepository sessionRepository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final ChatShareAccessPolicy accessPolicy;
    private final ChatShareSelectedMessagesProperties properties;
    private final ObjectMapper objectMapper;

    public SelectedChatShareApplicationService(
            ChatShareRepository shareRepository,
            ChatMessageRepository messageRepository,
            SessionRepository sessionRepository,
            IdGenerator idGenerator,
            PermissionChecker permissionChecker,
            ChatShareAccessPolicy accessPolicy,
            ChatShareSelectedMessagesProperties properties,
            ObjectMapper objectMapper) {
        this.shareRepository = shareRepository;
        this.messageRepository = messageRepository;
        this.sessionRepository = sessionRepository;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChatShare create(UserContext user, CreateSelectedChatShareCommand command) {
        permissionChecker.checkChatPermission(user);
        if (command == null) {
            throw new IllegalArgumentException("多消息分享请求不能为空");
        }
        Instant now = Instant.now();
        validateExpiresAt(command.expiresAt(), now);
        String sessionId = requireSessionId(command.sessionId());
        List<String> selectedIds = normalizeMessageIds(command.messageIds());
        ChatSession session = loadOwnedSession(user, sessionId);
        ensureSessionShareable(session);

        List<ChatMessage> selectedNodes = loadSelectedNodes(user, sessionId, selectedIds);
        validateSelectedNodes(user, selectedNodes);
        List<ChatMessage> orderedNodes = orderAndValidatePath(user, sessionId, selectedIds, selectedNodes);
        ChatShareSnapshot snapshot = buildSnapshot(user, sessionId, orderedNodes, now);
        validateSnapshotSize(snapshot);

        String sourceUserMessageId = firstMessageIdByRole(orderedNodes, ROLE_USER);
        String sourceAssistantMessageId = firstMessageIdByRole(orderedNodes, ROLE_ASSISTANT);
        ChatShare share = new ChatShare(
                idGenerator.newId("share", IdGenerateContext.of(
                        user.tenantId(), user.ownerUserId(), sessionId)),
                user.tenantId(),
                user.ownerUserId(),
                sessionId,
                sourceUserMessageId,
                sourceAssistantMessageId,
                null,
                titleOrDefault(command.title(), orderedNodes),
                "SELECTED_MESSAGES",
                "INTERNAL",
                "ACTIVE",
                command.expiresAt(),
                null,
                snapshot,
                now,
                now
        );
        return shareRepository.save(share);
    }

    private String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return sessionId.trim();
    }

    private List<String> normalizeMessageIds(List<String> rawIds) {
        List<String> source = rawIds == null ? List.of() : rawIds;
        if (source.size() > properties.requiredMaxMessages()) {
            throw new IllegalArgumentException("messageIds 数量超过限制: " + properties.requiredMaxMessages());
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String messageId : source) {
            if (messageId != null && !messageId.isBlank()) {
                normalized.add(messageId.trim());
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("messageIds 至少需要一个非空消息 ID");
        }
        return List.copyOf(normalized);
    }

    private ChatSession loadOwnedSession(UserContext user, String sessionId) {
        return sessionRepository.findByTenantIdAndUserIdAndId(
                        user.tenantId(), user.ownerUserId(), sessionId)
                .orElseThrow(() -> new IllegalArgumentException("会话不存在或不属于当前用户: " + sessionId));
    }

    private void ensureSessionShareable(ChatSession session) {
        if (STATUS_DELETED.equals(session.status())) {
            throw new IllegalArgumentException("已删除会话不能创建分享");
        }
    }

    private List<ChatMessage> loadSelectedNodes(
            UserContext user, String sessionId, List<String> selectedIds) {
        List<ChatMessage> nodes = messageRepository.findByOwnerAndSessionAndIds(
                user.tenantId(), user.ownerUserId(), sessionId, selectedIds);
        Set<String> foundIds = nodes.stream().map(ChatMessage::id).collect(Collectors.toSet());
        List<String> missingIds = selectedIds.stream().filter(id -> !foundIds.contains(id)).toList();
        if (!missingIds.isEmpty()) {
            throw new IllegalArgumentException("消息不存在或不属于当前用户及会话: " + String.join(",", missingIds));
        }
        return nodes;
    }

    private void validateSelectedNodes(UserContext user, List<ChatMessage> selectedNodes) {
        for (ChatMessage message : selectedNodes) {
            if (!ROLE_USER.equals(message.role()) && !ROLE_ASSISTANT.equals(message.role())) {
                throw new IllegalArgumentException("只能分享 user 或 assistant 消息: " + message.id());
            }
            if (!accessPolicy.canCreate(user, message)) {
                throw new SecurityException("无权分享消息: " + message.id());
            }
        }
    }

    private List<ChatMessage> orderAndValidatePath(
            UserContext user, String sessionId, List<String> selectedIds, List<ChatMessage> selectedNodes) {
        ChatMessage deepest = selectedNodes.stream().max(messagePathComparator())
                .orElseThrow(() -> new IllegalArgumentException("没有可分享的消息"));
        List<ChatMessage> path = messageRepository.findPathNodesToMessage(
                user.tenantId(), user.ownerUserId(), sessionId, deepest.id());
        Set<String> selectedIdSet = Set.copyOf(selectedIds);
        Set<String> pathIds = path.stream().map(ChatMessage::id).collect(Collectors.toSet());
        if (!pathIds.containsAll(selectedIdSet)) {
            throw new IllegalArgumentException("所选消息必须位于同一条会话分支");
        }
        List<ChatMessage> ordered = path.stream()
                .filter(message -> selectedIdSet.contains(message.id()))
                .sorted(messagePathComparator())
                .toList();
        if (ordered.size() != selectedIdSet.size()) {
            throw new IllegalArgumentException("所选消息路径不完整");
        }
        return ordered;
    }

    private Comparator<ChatMessage> messagePathComparator() {
        return Comparator.comparingInt((ChatMessage message) ->
                        message.treeDepth() == null ? 0 : message.treeDepth())
                .thenComparingLong(message -> message.nodeOrder() == null ? 0L : message.nodeOrder())
                .thenComparing(ChatMessage::createdAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(ChatMessage::id);
    }

    private ChatShareSnapshot buildSnapshot(
            UserContext user, String sessionId, List<ChatMessage> orderedNodes, Instant now) {
        List<String> orderedIds = orderedNodes.stream().map(ChatMessage::id).toList();
        Map<String, List<ChatMessageAttachment>> attachmentsByMessage = messageRepository
                .findAttachmentsByMessageIds(user.tenantId(), user.ownerUserId(), sessionId, orderedIds)
                .stream()
                .sorted(Comparator.comparingInt(ChatMessageAttachment::attachmentOrder))
                .collect(Collectors.groupingBy(ChatMessageAttachment::messageId,
                        LinkedHashMap::new, Collectors.toList()));
        Map<String, List<ChatMessagePart>> partsByMessage = messageRepository
                .findPartsByMessageIds(user.tenantId(), user.ownerUserId(), sessionId, orderedIds)
                .stream()
                .filter(part -> Boolean.TRUE.equals(part.visible()))
                .sorted(Comparator.comparingInt(part -> part.partOrder() == null ? 0 : part.partOrder()))
                .collect(Collectors.groupingBy(ChatMessagePart::messageId,
                        LinkedHashMap::new, Collectors.toList()));
        List<ChatShareSelectedMessageSnapshot> messages = orderedNodes.stream()
                .map(message -> toSelectedSnapshot(
                        message,
                        attachmentsByMessage.getOrDefault(message.id(), List.of()),
                        partsByMessage.getOrDefault(message.id(), List.of())))
                .toList();
        return new ChatShareSnapshot(null, null, List.of(), messages, now);
    }

    private ChatShareSelectedMessageSnapshot toSelectedSnapshot(
            ChatMessage message,
            List<ChatMessageAttachment> attachments,
            List<ChatMessagePart> parts) {
        return new ChatShareSelectedMessageSnapshot(
                message.id(),
                message.sessionId(),
                message.parentMessageId(),
                message.nodeOrder(),
                message.role(),
                message.content(),
                message.runId(),
                message.metadataJson(),
                attachments.stream().map(this::toAttachmentSnapshot).toList(),
                parts.stream().map(this::toSnapshotPart).toList(),
                message.createdAt()
        );
    }

    private ChatShareAttachmentSnapshot toAttachmentSnapshot(ChatMessageAttachment attachment) {
        return new ChatShareAttachmentSnapshot(
                attachment.documentId(), attachment.name(), attachment.contentType(), attachment.sizeBytes());
    }

    private ChatShareSnapshotPart toSnapshotPart(ChatMessagePart part) {
        return new ChatShareSnapshotPart(part.id(), part.messageId(), part.runId(), part.partType(),
                part.sourceType(), part.contentText(), part.title(), part.status(), part.channel(),
                part.displayHint(), part.visible(), part.payload(), part.partOrder(), part.createdAt());
    }

    private void validateSnapshotSize(ChatShareSnapshot snapshot) {
        final long snapshotBytes;
        try {
            snapshotBytes = objectMapper.writeValueAsBytes(snapshot).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("多消息分享快照序列化失败", ex);
        }
        if (snapshotBytes > properties.requiredMaxSnapshotBytes()) {
            throw new IllegalArgumentException("分享快照大小超过限制: "
                    + properties.requiredMaxSnapshotBytes() + " bytes");
        }
    }

    private String firstMessageIdByRole(List<ChatMessage> messages, String role) {
        return messages.stream()
                .filter(message -> role.equals(message.role()))
                .map(ChatMessage::id)
                .findFirst()
                .orElse(null);
    }

    private String titleOrDefault(String title, List<ChatMessage> messages) {
        String candidate = blankToNull(title);
        if (candidate == null) {
            candidate = messages.stream()
                    .filter(message -> ROLE_USER.equals(message.role()))
                    .map(ChatMessage::content)
                    .map(this::blankToNull)
                    .filter(value -> value != null)
                    .findFirst()
                    .orElse(null);
        }
        if (candidate == null) {
            candidate = messages.stream()
                    .map(ChatMessage::content)
                    .map(this::blankToNull)
                    .filter(value -> value != null)
                    .findFirst()
                    .orElse("消息分享");
        }
        String singleLine = candidate.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= MAX_TITLE_LENGTH
                ? singleLine
                : singleLine.substring(0, MAX_TITLE_LENGTH);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateExpiresAt(Instant expiresAt, Instant now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("expiresAt 必须晚于当前时间");
        }
    }
}
