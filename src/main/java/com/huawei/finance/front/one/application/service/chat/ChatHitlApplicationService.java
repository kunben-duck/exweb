package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.config.ChatHitlProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatHitlRequestRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import com.huawei.finance.front.one.domain.chat.ChatHitlStatus;
import com.huawei.finance.front.one.domain.chat.ChatHitlUnavailableException;
import com.huawei.finance.front.one.domain.chat.ChatHitlWaitingType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * HITL 等待用户输入应用服务。
 *
 * <p>本服务只管理跨请求等待态和续接事实，不解释 Relay questionnaire 的展示结构；
 * 展示快照仍通过 message parts 返回给前端。</p>
 */
@Service
public class ChatHitlApplicationService {
    private final ChatHitlRequestRepository repository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final ChatHitlProperties properties;

    public ChatHitlApplicationService(ChatHitlRequestRepository repository, IdGenerator idGenerator,
                                      PermissionChecker permissionChecker, ChatHitlProperties properties) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.properties = properties == null ? new ChatHitlProperties() : properties;
    }

    public Optional<ChatHitlRequest> findWaiting(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        return repository.findWaitingBySession(user.tenantId(), user.ownerUserId(), sessionId)
                .filter(request -> !expireIfNeeded(request, Instant.now()));
    }

    public void rejectIfWaiting(UserContext user, String sessionId) {
        findWaiting(user, sessionId)
                .ifPresent(request -> {
                    throw ChatHitlUnavailableException.waitingRequired(sessionId, request.id());
                });
    }

    public ChatHitlRequest prepareWaiting(ChatHitlCreateContext context) {
        Instant now = Instant.now();
        String id = idGenerator.newId("hitl",
                IdGenerateContext.of(context.user().tenantId(), context.user().ownerUserId(), context.session().id()));
        return new ChatHitlRequest(
                id,
                context.user().tenantId(),
                context.user().ownerUserId(),
                context.session().id(),
                context.sourceRunId(),
                null,
                context.userMessage().id(),
                context.assistantMessageId(),
                context.runtimeProvider(),
                context.runtimeBindingId(),
                context.runtimeSessionId(),
                approvalId(context.requestPayload()),
                waitingType(context.requestPayload()),
                ChatHitlStatus.WAITING,
                context.requestPayload(),
                Map.of(),
                properties.expiresAt(now),
                null,
                null,
                now,
                now
        );
    }

    public ChatHitlRequest saveWaiting(ChatHitlRequest request) {
        return repository.insert(request);
    }

    public ChatHitlClaimResult claimResponse(ChatHitlResponseCommand command, String continueRunId) {
        UserContext user = command.user();
        permissionChecker.checkChatPermission(user);
        Instant now = Instant.now();
        ChatHitlRequest request = repository.findByOwnerAndId(user.tenantId(), user.ownerUserId(), command.hitlRequestId())
                .orElseThrow(() -> new IllegalArgumentException("HITL 请求不存在: " + command.hitlRequestId()));
        if (expireIfNeeded(request, now)) {
            throw ChatHitlUnavailableException.expired(command.hitlRequestId());
        }
        if (!request.waiting()) {
            throw ChatHitlUnavailableException.alreadyHandled(command.hitlRequestId());
        }
        Map<String, Object> responsePayload = responsePayload(command);
        boolean claimed = repository.claimForResponse(new ChatHitlRequestRepository.ChatHitlClaimCommand(
                user.tenantId(), user.ownerUserId(), command.hitlRequestId(), continueRunId, responsePayload, now));
        if (!claimed) {
            throw ChatHitlUnavailableException.alreadyHandled(command.hitlRequestId());
        }
        ChatHitlRequest claimedRequest = repository.findByOwnerAndId(user.tenantId(), user.ownerUserId(),
                        command.hitlRequestId())
                .orElse(request);
        return new ChatHitlClaimResult(claimedRequest, responsePayload);
    }

    public void markAnswered(ChatHitlRequest request) {
        if (request != null) {
            repository.markAnswered(request.tenantId(), request.userId(), request.id(), Instant.now());
        }
    }

    public void markWaiting(ChatHitlRequest request) {
        if (request != null) {
            repository.markWaiting(request.tenantId(), request.userId(), request.id());
        }
    }

    public void cancelOpenBySession(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        repository.cancelOpenBySession(user.tenantId(), user.ownerUserId(), sessionId, Instant.now());
    }

    private boolean expireIfNeeded(ChatHitlRequest request, Instant now) {
        if (request.expiredAt(now)) {
            repository.markExpired(request.tenantId(), request.userId(), request.id());
            return true;
        }
        return false;
    }

    private Map<String, Object> responsePayload(ChatHitlResponseCommand command) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approved", command.approved());
        payload.put("scope", command.scope() == null || command.scope().isBlank() ? "once" : command.scope().trim());
        payload.put("questionnaireAnswers", command.questionnaireAnswers());
        payload.put("metadata", command.metadata());
        return Map.copyOf(payload);
    }

    private String approvalId(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("approval_id");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private ChatHitlWaitingType waitingType(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("waitingType");
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return ChatHitlWaitingType.valueOf(String.valueOf(value).trim());
            } catch (IllegalArgumentException ignored) {
                // 下游 payload 不是类型事实，未知值回落到 Agent 澄清，避免保存不可续接状态。
            }
        }
        return ChatHitlWaitingType.AGENT_CLARIFICATION;
    }
}
