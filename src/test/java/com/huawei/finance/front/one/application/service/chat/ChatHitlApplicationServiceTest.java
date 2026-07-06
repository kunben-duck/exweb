package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.ChatHitlProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatHitlRequestRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatHitlApplicationServiceTest {
    @Test
    void prepareWaitingUsesConfiguredTtl() {
        ChatHitlProperties properties = new ChatHitlProperties();
        properties.setDefaultExpireDuration(Duration.ofHours(2));
        ChatHitlApplicationService service = service(properties);
        Instant before = Instant.now();

        ChatHitlRequest request = service.prepareWaiting(context());

        assertThat(request.expiresAt()).isNotNull();
        assertThat(request.expiresAt()).isAfterOrEqualTo(before.plus(Duration.ofHours(2)));
        assertThat(request.expiresAt()).isBeforeOrEqualTo(Instant.now().plus(Duration.ofHours(2)));
    }

    @Test
    void prepareWaitingAllowsDisablingExpiration() {
        ChatHitlProperties properties = new ChatHitlProperties();
        properties.setDefaultExpireDuration(Duration.ZERO);
        ChatHitlApplicationService service = service(properties);

        ChatHitlRequest request = service.prepareWaiting(context());

        assertThat(request.expiresAt()).isNull();
    }

    private ChatHitlApplicationService service(ChatHitlProperties properties) {
        return new ChatHitlApplicationService(new UnusedHitlRepository(),
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), properties);
    }

    private ChatHitlCreateContext context() {
        Instant now = Instant.parse("2026-07-07T00:00:00Z");
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatSession session = new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "测试会话", "ACTIVE", "web", now, now);
        ChatMessage userMessage = new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), session.id(),
                "user", "请选择范围", null, now);
        return new ChatHitlCreateContext(user, session, "run1", userMessage, "msg-assistant",
                "relay", "binding1", "runtime-session1",
                Map.of("approval_id", "approval1", "operation_type", "questionnaire"));
    }

    private static class UnusedHitlRepository implements ChatHitlRequestRepository {
        @Override
        public ChatHitlRequest insert(ChatHitlRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChatHitlRequest> findByOwnerAndId(String tenantId, String userId, String hitlRequestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChatHitlRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean claimForResponse(ChatHitlClaimCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markAnswered(String tenantId, String userId, String hitlRequestId, Instant answeredAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markWaiting(String tenantId, String userId, String hitlRequestId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markExpired(String tenantId, String userId, String hitlRequestId) {
            throw new UnsupportedOperationException();
        }
    }
}
