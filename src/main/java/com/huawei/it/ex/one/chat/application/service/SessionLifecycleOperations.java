package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatMessageRepository;
import com.huawei.it.ex.one.chat.application.repository.SessionRepository;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.common.id.IdGenerateContext;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Package-local session lifecycle and path-selection policy. */
final class SessionLifecycleOperations {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String STATUS_DELETED = "DELETED";
    private static final int MAX_BATCH_DELETE_SIZE = 100;

    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final IdGenerator idGenerator;
    private final SessionMessageMutationService messageMutationService;
    private final SessionBranchService branchService;
    private final SessionQueryService queryService;
    private final SessionDeleteRunSupport deleteRunSupport;

    SessionLifecycleOperations(
            SessionRepository sessionRepository,
            ChatMessageRepository messageRepository,
            IdGenerator idGenerator,
            SessionMessageMutationService messageMutationService,
            SessionBranchService branchService,
            SessionQueryService queryService,
            SessionDeleteRunSupport deleteRunSupport) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
        this.idGenerator = idGenerator;
        this.messageMutationService = messageMutationService;
        this.branchService = branchService;
        this.queryService = queryService;
        this.deleteRunSupport = deleteRunSupport;
    }

    ChatSession loadOrCreate(ChatCommand command) {
        if (command.sessionId() == null || command.sessionId().isBlank()) {
            return createOwnedSession(
                    command.tenantId(),
                    command.userId(),
                    shortTitle(command.message()),
                    command.channel(),
                    new SessionAppTag(command.appId(), command.appName()));
        }
        ChatSession session = requireOwnedSession(
                command.tenantId(), command.userId(), command.sessionId());
        validateAppTag(session, command.appId(), command.appName());
        return touch(session);
    }

    ChatSession createSession(
            UserContext user,
            String title,
            String channel,
            String appId,
            String appName) {
        checkChatUser(user);
        return createOwnedSession(
                user.tenantId(),
                user.ownerUserId(),
                title,
                channel,
                new SessionAppTag(appId, appName));
    }

    ChatSession renameSession(UserContext user, String sessionId, String title) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(
                user.tenantId(), user.ownerUserId(), sessionId, false);
        String safeTitle = title == null || title.isBlank() ? session.title() : title.trim();
        return saveWith(session, safeTitle, session.status());
    }

    ChatSession archiveSession(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(
                user.tenantId(), user.ownerUserId(), sessionId, false);
        return saveWith(session, session.title(), STATUS_ARCHIVED);
    }

    ChatSession restoreSession(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(
                user.tenantId(), user.ownerUserId(), sessionId, false);
        return saveWith(session, session.title(), STATUS_ACTIVE);
    }

    List<ChatSession> deleteSessions(UserContext user, List<String> sessionIds) {
        checkChatUser(user);
        List<String> normalizedIds = normalizeDeleteSessionIds(sessionIds);
        List<ChatSession> sessions = normalizedIds.stream()
                .map(sessionId -> requireOwnedSession(
                        user.tenantId(), user.ownerUserId(), sessionId, false))
                .toList();
        List<SessionDeleteRunSupport.DeleteRunPlan> activeRunPlans =
                deleteRunSupport.activeRunPlans(user, sessions);
        List<ChatSession> deleted = new ArrayList<>(sessions.size());
        for (ChatSession session : sessions) {
            ChatSession deletedSession = saveWith(session, session.title(), STATUS_DELETED);
            deleteRunSupport.afterSessionDeleted(user, session);
            deleted.add(deletedSession);
        }
        deleteRunSupport.stopAfterCommit(user, activeRunPlans);
        return List.copyOf(deleted);
    }

    void lockForMessageMutation(String tenantId, String userId, ChatSession session) {
        if (tenantId == null || tenantId.isBlank()
                || userId == null || userId.isBlank()
                || session == null) {
            throw new IllegalArgumentException("会话消息写入锁参数不完整");
        }
        if (!tenantId.equals(session.tenantId()) || !userId.equals(session.userId())) {
            throw new SecurityException("会话不属于当前用户");
        }
        sessionRepository.lockForMessageMutation(tenantId, userId, session.id());
    }

    void advanceLatestMessageSeq(UserContext user, ChatSession session, long messageSeq) {
        if (user == null || session == null || messageSeq < 0L) {
            throw new IllegalArgumentException("会话消息水位参数不合法");
        }
        sessionRepository.advanceLatestMessageSeq(
                user.tenantId(), user.ownerUserId(), session.id(), messageSeq);
    }

    ChatSession selectPath(UserContext user, String sessionId, String leafMessageId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(
                user.tenantId(), user.ownerUserId(), sessionId, false);
        ChatMessage selected = messageMutationService.requireMessageInSession(session, leafMessageId);
        String effectiveLeafMessageId = effectiveLeafForPathSelection(user, session, selected);
        sessionRepository.updateCurrentLeaf(
                user.tenantId(), user.ownerUserId(), session.id(), effectiveLeafMessageId);
        return requireOwnedSession(user.tenantId(), user.ownerUserId(), session.id(), false);
    }

    ChatSession createBranch(
            UserContext user,
            String sessionId,
            String sourceMessageId,
            String title) {
        checkChatUser(user);
        ChatSession sourceSession = requireOwnedSession(
                user.tenantId(), user.ownerUserId(), sessionId, false);
        String branchId = branchService.createBranch(
                user, sourceSession, sourceMessageId, title);
        return requireOwnedSession(user.tenantId(), user.ownerUserId(), branchId, false);
    }

    void validateAppTag(
            UserContext user,
            String sessionId,
            String appId,
            String appName) {
        checkChatUser(user);
        validateAppTag(
                requireOwnedSession(user.tenantId(), user.ownerUserId(), sessionId),
                appId,
                appName);
    }

    private ChatSession createOwnedSession(
            String tenantId,
            String userId,
            String title,
            String channel,
            SessionAppTag appTag) {
        Instant now = Instant.now();
        String sessionId = idGenerator.newId("session", IdGenerateContext.of(tenantId, userId));
        String safeTitle = title == null || title.isBlank() ? "新会话" : title;
        String safeChannel = channel == null || channel.isBlank() ? "web" : channel;
        return sessionRepository.save(new ChatSession(
                sessionId,
                tenantId,
                userId,
                safeTitle,
                STATUS_ACTIVE,
                safeChannel,
                appTag.appId(),
                appTag.appName(),
                null,
                sessionId,
                null,
                null,
                0L,
                null,
                now,
                now));
    }

    private ChatSession touch(ChatSession session) {
        ChatSession touched = new ChatSession(
                session.id(),
                session.tenantId(),
                session.userId(),
                session.title(),
                session.status(),
                session.channel(),
                session.appId(),
                session.appName(),
                session.currentLeafMessageId(),
                session.rootSessionId(),
                session.branchSourceSessionId(),
                session.branchSourceMessageId(),
                session.lastNodeOrder(),
                session.latestMessageSeq(),
                session.lastReadSeq(),
                session.metadataJson(),
                session.createdAt(),
                Instant.now());
        return sessionRepository.save(touched);
    }

    private ChatSession saveWith(ChatSession session, String title, String status) {
        ChatSession updated = new ChatSession(
                session.id(),
                session.tenantId(),
                session.userId(),
                title,
                status,
                session.channel(),
                session.appId(),
                session.appName(),
                session.currentLeafMessageId(),
                session.rootSessionId(),
                session.branchSourceSessionId(),
                session.branchSourceMessageId(),
                session.lastNodeOrder(),
                session.latestMessageSeq(),
                session.lastReadSeq(),
                session.metadataJson(),
                session.createdAt(),
                Instant.now());
        return sessionRepository.save(updated);
    }

    private void validateAppTag(ChatSession session, String appId, String appName) {
        String normalizedAppId = normalizeTag(appId);
        String normalizedAppName = normalizeTag(appName);
        if (normalizedAppId == null && normalizedAppName != null) {
            throw new IllegalArgumentException("appName 不能脱离 appId 单独使用");
        }
        if (normalizedAppId != null && !normalizedAppId.equals(session.appId())) {
            throw new IllegalArgumentException("appId 与已有会话不一致");
        }
        if (normalizedAppName != null && !normalizedAppName.equals(session.appName())) {
            throw new IllegalArgumentException("appName 与已有会话不一致");
        }
    }

    private String effectiveLeafForPathSelection(
            UserContext user,
            ChatSession session,
            ChatMessage selected) {
        if (!"user".equalsIgnoreCase(selected.role())) {
            return selected.id();
        }
        return messageRepository.findSiblings(
                        user.tenantId(),
                        user.ownerUserId(),
                        session.id(),
                        selected.id(),
                        "assistant")
                .stream()
                .reduce((previous, current) -> current)
                .map(ChatMessage::id)
                .orElse(selected.id());
    }

    private List<String> normalizeDeleteSessionIds(List<String> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            throw new IllegalArgumentException("sessionIds 不能为空");
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String sessionId : sessionIds) {
            if (sessionId == null || sessionId.isBlank()) {
                continue;
            }
            normalized.add(sessionId.trim());
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("sessionIds 不能为空");
        }
        if (normalized.size() > MAX_BATCH_DELETE_SIZE) {
            throw new IllegalArgumentException("单次最多删除 " + MAX_BATCH_DELETE_SIZE + " 个会话");
        }
        return List.copyOf(normalized);
    }

    private ChatSession requireOwnedSession(String tenantId, String userId, String sessionId) {
        return queryService.requireOwnedSession(tenantId, userId, sessionId);
    }

    private ChatSession requireOwnedSession(
            String tenantId,
            String userId,
            String sessionId,
            boolean activeRequired) {
        return queryService.requireOwnedSession(tenantId, userId, sessionId, activeRequired);
    }

    private void checkChatUser(UserContext user) {
        queryService.checkChatUser(user);
    }

    private String shortTitle(String text) {
        return text == null ? "新会话" : text.substring(0, Math.min(40, text.length()));
    }

    private String normalizeTag(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record SessionAppTag(String appId, String appName) {
    }
}
