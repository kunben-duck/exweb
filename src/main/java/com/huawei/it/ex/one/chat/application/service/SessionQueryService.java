package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.chat.application.model.ChatMessagePageQuery;
import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatMessagePage;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.ChatSessionNumberPage;
import com.huawei.it.ex.one.chat.domain.ChatSessionPage;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

final class SessionQueryService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_DELETED = "DELETED";

    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final PermissionChecker permissionChecker;
    private final SessionMessageMutationService messageMutationService;

    SessionQueryService(SessionRepository sessionRepository,
                        ChatMessageRepository messageRepository,
                        PermissionChecker permissionChecker,
                        SessionMessageMutationService messageMutationService) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.permissionChecker = permissionChecker;
        this.messageMutationService = messageMutationService;
    }

    ChatSession getSession(UserContext user, String sessionId) {
        checkChatUser(user);
        return requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId);
    }

    ChatSession markSessionRead(UserContext user, String sessionId, long readThroughSeq) {
        checkChatUser(user);
        if (readThroughSeq < 0L) {
            throw new IllegalArgumentException("readThroughSeq 不能小于 0");
        }
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return sessionRepository.markReadThrough(
                session.tenantId(), session.userId(), session.id(), readThroughSeq);
    }

    ChatMessagePage listMessages(UserContext user, String sessionId, String leafMessageId,
                                 String cursor, int limit) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        if (leafMessageId != null && !leafMessageId.isBlank()) {
            messageMutationService.requireMessageInSession(session, leafMessageId);
        }
        return messageRepository.pageMessages(new ChatMessagePageQuery(
                session.tenantId(), session.userId(), session.id(), leafMessageId, cursor, limit));
    }

    List<ChatMessage> listMessageTree(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return messageRepository.findAllBySession(session.tenantId(), session.userId(), session.id());
    }

    List<ChatMessage> listMessageTreeNodes(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        return messageRepository.findAllMessageNodesBySession(
                session.tenantId(), session.userId(), session.id());
    }

    ChatSessionPage listSessions(UserContext user, String appId, String cursor, int limit) {
        checkChatUser(user);
        return sessionRepository.pageByTenantIdAndUserId(
                user.tenantId(), user.ownerUserId(), normalizeTag(appId), cursor, limit);
    }

    ChatSessionNumberPage listSessionsByPage(UserContext user, String appId, int curPage, int pageSize) {
        checkChatUser(user);
        return sessionRepository.pageNumberByTenantIdAndUserId(
                user.tenantId(), user.ownerUserId(), normalizeTag(appId), curPage, pageSize);
    }

    Map<String, String> findFirstAssistantAnswers(UserContext user, List<ChatSession> sessions) {
        checkChatUser(user);
        if (sessions == null || sessions.isEmpty()) {
            return Map.of();
        }
        List<String> sessionIds = sessions.stream()
                .filter(session -> session != null)
                .filter(session -> user.tenantId().equals(session.tenantId()))
                .filter(session -> user.ownerUserId().equals(session.userId()))
                .map(ChatSession::id)
                .distinct()
                .toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return messageRepository.findFirstAssistantMessagesBySessionIds(
                        user.tenantId(), user.ownerUserId(), sessionIds)
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey,
                        entry -> entry.getValue().content() == null ? "" : entry.getValue().content()));
    }

    List<ChatMessage> listVariants(UserContext user, String sessionId, String messageId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId, false);
        ChatMessage message = messageMutationService.requireMessageInSession(session, messageId);
        return messageRepository.findSiblings(user.tenantId(), user.ownerUserId(), session.id(),
                message.parentMessageId(), message.role());
    }

    void checkChatUser(UserContext user) {
        permissionChecker.checkChatPermission(user);
    }

    ChatSession requireOwnedSession(String tenantId, String userId, String sessionId) {
        return requireOwnedSession(tenantId, userId, sessionId, true);
    }

    ChatSession requireOwnedSession(String tenantId, String userId, String sessionId, boolean activeRequired) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return sessionRepository.findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .map(session -> activeRequired ? ensureActive(session) : ensureNotDeleted(session))
                .orElseThrow(() -> sessionRepository.findById(sessionId).isPresent()
                        ? new SecurityException("会话不属于当前用户")
                        : new IllegalArgumentException("会话不存在: " + sessionId));
    }

    private ChatSession ensureActive(ChatSession session) {
        ensureNotDeleted(session);
        if (!STATUS_ACTIVE.equals(session.status())) {
            throw new IllegalStateException("会话不可用: " + session.id());
        }
        return session;
    }

    private ChatSession ensureNotDeleted(ChatSession session) {
        if (STATUS_DELETED.equals(session.status())) {
            throw new IllegalArgumentException("会话不存在: " + session.id());
        }
        return session;
    }

    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
