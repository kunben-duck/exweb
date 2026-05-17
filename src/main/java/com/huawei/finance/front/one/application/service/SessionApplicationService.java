package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.ChatSessionFacade;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatMessagePage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.ChatSessionPage;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 会话与消息应用服务。
 *
 * <p>负责会话创建、用户消息落库和助手最终回复落库。</p>
 */
@Service
public class SessionApplicationService implements ChatSessionFacade {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ARCHIVED = "ARCHIVED";
    private static final String STATUS_CLOSED = "CLOSED";

    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;

    public SessionApplicationService(SessionRepository sessionRepository, ChatMessageRepository messageRepository, IdGenerator idGenerator,
                                     PermissionChecker permissionChecker) {
        this.sessionRepository = sessionRepository; this.messageRepository = messageRepository; this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
    }

    public ChatSession loadOrCreate(ChatCommand command) {
        // 聊天主编排会先把 UserContext 回填到 ChatCommand；这里只根据已识别身份维护会话归属。
        if (command.sessionId() == null || command.sessionId().isBlank()) {
            return createOwnedSession(command.tenantId(), command.userId(), shortTitle(command.message()), command.channel());
        }
        return touch(requireOwnedSession(command.tenantId(), command.userId(), command.sessionId()));
    }

    @Override
    public ChatSession createSession(UserContext user, String title, String channel) {
        checkChatUser(user);
        return createOwnedSession(user.tenantId(), user.userId(), title, channel);
    }

    @Override
    public ChatSession getSession(UserContext user, String sessionId) {
        checkChatUser(user);
        return requireOwnedSession(user.tenantId(), user.userId(), sessionId);
    }

    @Override
    public ChatMessagePage listMessages(UserContext user, String sessionId, String cursor, int limit) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.userId(), sessionId, false);
        return messageRepository.pageMessages(session.tenantId(), session.userId(), session.id(), cursor, limit);
    }

    @Override
    public ChatSessionPage listSessions(UserContext user, String cursor, int limit) {
        checkChatUser(user);
        return sessionRepository.pageByTenantIdAndUserId(user.tenantId(), user.userId(), cursor, limit);
    }

    @Override
    public Optional<ChatMessage> latestUserMessage(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.userId(), sessionId, false);
        return latestUserMessage(session.tenantId(), session.userId(), session.id());
    }

    /**
     * 查询指定会话最近一条用户消息，供 run retry 在未传新文本时复用上一轮输入。
     *
     * <p>该方法接收显式 owner，是因为聊天主编排已经解析并校验过 UserContext，避免再次从
     * ThreadLocal/请求上下文读取身份导致异步 run 中上下文不一致。</p>
     */
    public Optional<ChatMessage> latestUserMessage(String tenantId, String userId, String sessionId) {
        return messageRepository.findRecentMessages(tenantId, userId, sessionId, 50).stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst();
    }

    @Override
    public ChatSession renameSession(UserContext user, String sessionId, String title) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.userId(), sessionId, false);
        String safeTitle = title == null || title.isBlank() ? session.title() : title.trim();
        return saveWith(session, safeTitle, session.status());
    }

    @Override
    public ChatSession archiveSession(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.userId(), sessionId, false);
        return saveWith(session, session.title(), STATUS_ARCHIVED);
    }

    @Override
    public ChatSession restoreSession(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.userId(), sessionId, false);
        return saveWith(session, session.title(), STATUS_ACTIVE);
    }

    @Override
    public ChatSession closeSession(UserContext user, String sessionId) {
        checkChatUser(user);
        ChatSession session = requireOwnedSession(user.tenantId(), user.userId(), sessionId);
        return saveWith(session, session.title(), STATUS_CLOSED);
    }

    public ChatMessage saveUserMessage(ChatCommand command, ChatSession session) {
        // 用户原始输入单独保存，后续用于上下文回放和审计。
        String messageId = idGenerator.newId("msg", IdGenerateContext.of(command.tenantId(), command.userId(), session.id()));
        return messageRepository.save(new ChatMessage(messageId, command.tenantId(), command.userId(), session.id(), "user", command.message(), null, Instant.now()));
    }
    public ChatMessage saveAssistantMessage(String tenantId, String userId, String sessionId, String content) {
        // 助手消息在事件流结束后保存完整文本，避免保存大量碎片 delta。
        String messageId = idGenerator.newId("msg", IdGenerateContext.of(tenantId, userId, sessionId));
        return messageRepository.save(new ChatMessage(messageId, tenantId, userId, sessionId, "assistant", content, null, Instant.now()));
    }
    private String shortTitle(String text) { return text == null ? "新会话" : text.substring(0, Math.min(40, text.length())); }

    private void checkChatUser(UserContext user) {
        permissionChecker.checkChatPermission(user);
    }

    private ChatSession createOwnedSession(String tenantId, String userId, String title, String channel) {
        Instant now = Instant.now();
        String sessionId = idGenerator.newId("session", IdGenerateContext.of(tenantId, userId));
        String safeTitle = title == null || title.isBlank() ? "新会话" : title;
        String safeChannel = channel == null || channel.isBlank() ? "web" : channel;
        return sessionRepository.save(new ChatSession(sessionId, tenantId, userId, safeTitle, STATUS_ACTIVE, safeChannel, now, now));
    }

    private ChatSession requireOwnedSession(String tenantId, String userId, String sessionId) {
        return requireOwnedSession(tenantId, userId, sessionId, true);
    }

    private ChatSession requireOwnedSession(String tenantId, String userId, String sessionId, boolean activeRequired) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return sessionRepository.findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .map(session -> activeRequired ? ensureActive(session) : session)
                .orElseThrow(() -> sessionRepository.findById(sessionId).isPresent()
                        ? new SecurityException("会话不属于当前用户")
                        : new IllegalArgumentException("会话不存在: " + sessionId));
    }

    private ChatSession ensureActive(ChatSession session) {
        if (!STATUS_ACTIVE.equals(session.status())) {
            throw new IllegalStateException("会话不可用: " + session.id());
        }
        return session;
    }

    private ChatSession touch(ChatSession session) {
        ChatSession touched = new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(), session.status(), session.channel(), session.createdAt(), Instant.now());
        return sessionRepository.save(touched);
    }

    private ChatSession saveWith(ChatSession session, String title, String status) {
        ChatSession updated = new ChatSession(session.id(), session.tenantId(), session.userId(), title, status,
                session.channel(), session.createdAt(), Instant.now());
        return sessionRepository.save(updated);
    }
}
