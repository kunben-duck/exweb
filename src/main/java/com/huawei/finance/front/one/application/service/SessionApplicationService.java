package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.facade.ChatSessionFacade;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.integration.memory.ChatMessageRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.integration.conversation.SessionRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 会话与消息应用服务。
 *
 * <p>负责会话创建、用户消息落库和助手最终回复落库。</p>
 */
@Service
public class SessionApplicationService implements ChatSessionFacade {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_CLOSED = "CLOSED";

    private final SessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final IdGenerator idGenerator;
    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;

    public SessionApplicationService(SessionRepository sessionRepository, ChatMessageRepository messageRepository, IdGenerator idGenerator,
                                     AuthContextProvider auth, PermissionChecker permissionChecker) {
        this.sessionRepository = sessionRepository; this.messageRepository = messageRepository; this.idGenerator = idGenerator;
        this.auth = auth; this.permissionChecker = permissionChecker;
    }

    public ChatSession loadOrCreate(ChatCommand command) {
        // 聊天主编排会先把 UserContext 回填到 ChatCommand；这里只根据已识别身份维护会话归属。
        if (command.sessionId() == null || command.sessionId().isBlank()) {
            return createOwnedSession(command.tenantId(), command.userId(), shortTitle(command.message()), command.channel());
        }
        return touch(requireOwnedSession(command.tenantId(), command.userId(), command.sessionId()));
    }

    @Override
    public ChatSession createSession(String title, String channel) {
        UserContext user = resolveChatUser();
        return createOwnedSession(user.tenantId(), user.userId(), title, channel);
    }

    @Override
    public ChatSession getSession(String sessionId) {
        UserContext user = resolveChatUser();
        return requireOwnedSession(user.tenantId(), user.userId(), sessionId);
    }

    @Override
    public List<ChatSession> listSessions() {
        UserContext user = resolveChatUser();
        return sessionRepository.findByTenantIdAndUserId(user.tenantId(), user.userId());
    }

    @Override
    public ChatSession closeSession(String sessionId) {
        UserContext user = resolveChatUser();
        ChatSession session = requireOwnedSession(user.tenantId(), user.userId(), sessionId);
        ChatSession closed = new ChatSession(session.id(), session.tenantId(), session.userId(), session.title(), STATUS_CLOSED, session.channel(), session.createdAt(), Instant.now());
        return sessionRepository.save(closed);
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

    private UserContext resolveChatUser() {
        UserContext user = auth.resolve();
        permissionChecker.checkChatPermission(user);
        return user;
    }

    private ChatSession createOwnedSession(String tenantId, String userId, String title, String channel) {
        Instant now = Instant.now();
        String sessionId = idGenerator.newId("session", IdGenerateContext.of(tenantId, userId));
        String safeTitle = title == null || title.isBlank() ? "新会话" : title;
        String safeChannel = channel == null || channel.isBlank() ? "web" : channel;
        return sessionRepository.save(new ChatSession(sessionId, tenantId, userId, safeTitle, STATUS_ACTIVE, safeChannel, now, now));
    }

    private ChatSession requireOwnedSession(String tenantId, String userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        return sessionRepository.findByTenantIdAndUserIdAndId(tenantId, userId, sessionId)
                .map(this::ensureActive)
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
}
