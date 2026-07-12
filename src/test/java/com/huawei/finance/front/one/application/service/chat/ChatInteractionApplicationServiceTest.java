package com.huawei.finance.front.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.finance.front.one.application.config.ChatInteractionProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatInteractionRequest;
import com.huawei.finance.front.one.domain.chat.ChatInteractionStatus;
import com.huawei.finance.front.one.domain.chat.ChatInteractionType;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ChatInteractionApplicationServiceTest {
    @Test
    void prepareInteractionUsesConfiguredTtl() {
        ChatInteractionProperties properties = new ChatInteractionProperties();
        properties.setDefaultExpireDuration(Duration.ofHours(2));
        ChatInteractionApplicationService service = service(properties);
        Instant before = Instant.now();

        ChatInteractionRequest request = service.prepareInteraction(context());

        assertThat(request.expiresAt()).isNotNull();
        assertThat(request.expiresAt()).isAfterOrEqualTo(before.plus(Duration.ofHours(2)));
        assertThat(request.expiresAt()).isBeforeOrEqualTo(Instant.now().plus(Duration.ofHours(2)));
    }

    @Test
    void prepareInteractionAllowsDisablingExpiration() {
        ChatInteractionProperties properties = new ChatInteractionProperties();
        properties.setDefaultExpireDuration(Duration.ZERO);
        ChatInteractionApplicationService service = service(properties);

        ChatInteractionRequest request = service.prepareInteraction(context());

        assertThat(request.expiresAt()).isNull();
    }

    @Test
    void intentClarificationResponseDefaultsApprovedAndScope() {
        MutableInteractionRepository repository = new MutableInteractionRepository();
        ChatInteractionRequest waiting = waitingRequest(ChatInteractionType.INTENT_CLARIFICATION);
        repository.insert(waiting);
        ChatInteractionApplicationService service = new ChatInteractionApplicationService(repository,
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), new ChatInteractionProperties());

        ChatInteractionClaimResult claim = service.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), waiting.id(), null, null, Map.of("问题", "答案"), Map.of()), "run-continue");

        assertThat(claim.responsePayload())
                .containsEntry("approved", Boolean.TRUE)
                .containsEntry("scope", "once")
                .containsEntry("questionnaireAnswers", Map.of("问题", "答案"))
                .containsEntry("answerText", "答案");
        assertThat(repository.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.RESPONDING);
    }

    @Test
    void intentClarificationResponseRequiresAnswersBeforeClaim() {
        MutableInteractionRepository repository = new MutableInteractionRepository();
        ChatInteractionRequest waiting = waitingRequest(ChatInteractionType.INTENT_CLARIFICATION);
        repository.insert(waiting);
        ChatInteractionApplicationService service = new ChatInteractionApplicationService(repository,
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), new ChatInteractionProperties());

        assertThatThrownBy(() -> service.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), waiting.id(), null, null, Map.of(), Map.of()), "run-continue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("questionnaireAnswers");
        assertThat(repository.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
    }

    @Test
    void intentClarificationResponseRejectsBlankAnswersBeforeClaim() {
        MutableInteractionRepository repository = new MutableInteractionRepository();
        ChatInteractionRequest waiting = waitingRequest(ChatInteractionType.INTENT_CLARIFICATION);
        repository.insert(waiting);
        ChatInteractionApplicationService service = new ChatInteractionApplicationService(repository,
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), new ChatInteractionProperties());

        assertThatThrownBy(() -> service.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), waiting.id(), null, null, Map.of("问题", "  "), Map.of()), "run-continue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("非空答案");
        assertThat(repository.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
    }

    @Test
    void intentClarificationResponseBuildsStableMultiAnswerText() {
        MutableInteractionRepository repository = new MutableInteractionRepository();
        ChatInteractionRequest waiting = waitingRequest(ChatInteractionType.INTENT_CLARIFICATION);
        repository.insert(waiting);
        ChatInteractionApplicationService service = new ChatInteractionApplicationService(repository,
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), new ChatInteractionProperties());

        ChatInteractionClaimResult claim = service.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), waiting.id(), null, null,
                Map.of("税种", "增值税", "期间", "2026年7月"), Map.of()), "run-continue");

        assertThat(claim.responsePayload()).containsEntry(
                "answerText", "期间：2026年7月\n税种：增值税");
    }

    @Test
    void routeSwitchConfirmationStillRequiresApproved() {
        MutableInteractionRepository repository = new MutableInteractionRepository();
        ChatInteractionRequest waiting = waitingRequest(ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        repository.insert(waiting);
        ChatInteractionApplicationService service = new ChatInteractionApplicationService(repository,
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), new ChatInteractionProperties());

        assertThatThrownBy(() -> service.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), waiting.id(), null, null, Map.of("问题", "答案"), Map.of()), "run-continue"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("approved");
        assertThat(repository.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
    }

    @Test
    void markWaitingForRunOnlyReleasesMatchingContinuationClaim() {
        MutableInteractionRepository repository = new MutableInteractionRepository();
        ChatInteractionRequest waiting = waitingRequest(ChatInteractionType.INTENT_CLARIFICATION);
        repository.insert(waiting);
        ChatInteractionApplicationService service = new ChatInteractionApplicationService(repository,
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), new ChatInteractionProperties());
        service.claimInteractionResponse(new ChatInteractionResponseCommand(
                user(), waiting.id(), null, null, Map.of("问题", "答案"), Map.of()), "run-current");

        service.markWaitingForRun(user().tenantId(), user().ownerUserId(), waiting.id(), "run-stale");
        assertThat(repository.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.RESPONDING);

        service.markWaitingForRun(user().tenantId(), user().ownerUserId(), waiting.id(), "run-current");
        assertThat(repository.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(repository.requests.get(waiting.id()).continueRunId()).isNull();
    }

    private ChatInteractionApplicationService service(ChatInteractionProperties properties) {
        return new ChatInteractionApplicationService(new UnusedInteractionRepository(),
                (bizType, context) -> bizType + "_fixed", new PermissionChecker(), properties);
    }

    private ChatInteractionCreateContext context() {
        Instant now = Instant.parse("2026-07-07T00:00:00Z");
        UserContext user = user();
        ChatSession session = new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "测试会话", "ACTIVE", "web", now, now);
        ChatMessage userMessage = new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), session.id(),
                "user", "请选择范围", null, now);
        return new ChatInteractionCreateContext(user, session, "run1", userMessage, "msg-assistant",
                "relay", "binding1", "runtime-session1",
                Map.of("approval_id", "approval1", "operation_type", "questionnaire"));
    }

    private UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }

    private ChatInteractionRequest waitingRequest(ChatInteractionType interactionType) {
        Instant now = Instant.now();
        UserContext user = user();
        return new ChatInteractionRequest(
                "interaction1",
                user.tenantId(),
                user.ownerUserId(),
                "session1",
                "run-source",
                null,
                "msg-user",
                "msg-assistant",
                interactionType == ChatInteractionType.INTENT_CLARIFICATION ? "intent-agent" : "relay",
                interactionType == ChatInteractionType.INTENT_CLARIFICATION ? null : "binding1",
                "session1",
                null,
                interactionType,
                ChatInteractionStatus.WAITING,
                Map.of("interactionType", interactionType.name()),
                Map.of(),
                now.plus(Duration.ofHours(1)),
                null,
                null,
                now,
                now);
    }

    private static class UnusedInteractionRepository implements ChatInteractionRequestRepository {
        @Override
        public ChatInteractionRequest insert(ChatInteractionRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChatInteractionRequest> findByOwnerAndId(String tenantId, String userId, String interactionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<ChatInteractionRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean claimInteractionResponse(ChatInteractionClaimCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markAnswered(String tenantId, String userId, String interactionId, Instant answeredAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markWaiting(String tenantId, String userId, String interactionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markWaitingForRun(String tenantId, String userId, String interactionId, String continueRunId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int markExpired(String tenantId, String userId, String interactionId) {
            throw new UnsupportedOperationException();
        }
    }

    private static class MutableInteractionRepository implements ChatInteractionRequestRepository {
        private final Map<String, ChatInteractionRequest> requests = new java.util.HashMap<>();

        @Override
        public ChatInteractionRequest insert(ChatInteractionRequest request) {
            requests.put(request.id(), request);
            return request;
        }

        @Override
        public Optional<ChatInteractionRequest> findByOwnerAndId(String tenantId, String userId, String interactionId) {
            return Optional.ofNullable(requests.get(interactionId))
                    .filter(request -> tenantId.equals(request.tenantId()))
                    .filter(request -> userId.equals(request.userId()));
        }

        @Override
        public Optional<ChatInteractionRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
            return requests.values().stream()
                    .filter(request -> tenantId.equals(request.tenantId()))
                    .filter(request -> userId.equals(request.userId()))
                    .filter(request -> sessionId.equals(request.sessionId()))
                    .filter(ChatInteractionRequest::waiting)
                    .findFirst();
        }

        @Override
        public boolean claimInteractionResponse(ChatInteractionClaimCommand command) {
            ChatInteractionRequest current = requests.get(command.interactionId());
            if (current == null || !current.waiting()) {
                return false;
            }
            requests.put(current.id(), new ChatInteractionRequest(
                    current.id(), current.tenantId(), current.userId(), current.sessionId(), current.sourceRunId(),
                    command.continueRunId(), current.userMessageId(), current.assistantMessageId(),
                    current.runtimeProvider(), current.runtimeBindingId(), current.runtimeSessionId(),
                    current.approvalId(), current.interactionType(), ChatInteractionStatus.RESPONDING,
                    current.requestPayload(), command.responsePayload(), current.expiresAt(), current.answeredAt(),
                    current.cancelledAt(), current.createdAt(), command.now()));
            return true;
        }

        @Override public int markAnswered(String tenantId, String userId, String interactionId, Instant answeredAt) {
            return 0;
        }
        @Override public int markWaiting(String tenantId, String userId, String interactionId) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                    || current.status() != ChatInteractionStatus.RESPONDING) {
                return 0;
            }
            requests.put(interactionId, withWaitingStatus(current));
            return 1;
        }
        @Override public int markWaitingForRun(String tenantId, String userId, String interactionId,
                                               String continueRunId) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                    || current.status() != ChatInteractionStatus.RESPONDING
                    || !continueRunId.equals(current.continueRunId())) {
                return 0;
            }
            requests.put(interactionId, withWaitingStatus(current));
            return 1;
        }
        @Override public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
            return 0;
        }
        @Override public int markExpired(String tenantId, String userId, String interactionId) { return 0; }

        private ChatInteractionRequest withWaitingStatus(ChatInteractionRequest current) {
            return new ChatInteractionRequest(
                    current.id(), current.tenantId(), current.userId(), current.sessionId(), current.sourceRunId(),
                    null, current.userMessageId(), current.assistantMessageId(), current.runtimeProvider(),
                    current.runtimeBindingId(), current.runtimeSessionId(), current.approvalId(),
                    current.interactionType(), ChatInteractionStatus.WAITING, current.requestPayload(),
                    current.responsePayload(), current.expiresAt(), current.answeredAt(), current.cancelledAt(),
                    current.createdAt(), Instant.now());
        }
    }
}
