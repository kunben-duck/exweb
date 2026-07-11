package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.config.ChatInteractionProperties;
import com.huawei.finance.front.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.finance.front.one.application.integration.id.IdGenerateContext;
import com.huawei.finance.front.one.application.integration.id.IdGenerator;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatInteractionRequest;
import com.huawei.finance.front.one.domain.chat.ChatInteractionStatus;
import com.huawei.finance.front.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.finance.front.one.domain.chat.ChatInteractionType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Interaction 等待用户输入应用服务。
 *
 * <p>本服务只管理跨请求等待态和续接事实，不解释 Relay questionnaire 的展示结构；
 * 展示快照仍通过 message parts 返回给前端。</p>
 */
@Service
public class ChatInteractionApplicationService {
    private static final Logger log = LoggerFactory.getLogger(ChatInteractionApplicationService.class);

    private final ChatInteractionRequestRepository repository;
    private final IdGenerator idGenerator;
    private final PermissionChecker permissionChecker;
    private final ChatInteractionProperties properties;

    public ChatInteractionApplicationService(ChatInteractionRequestRepository repository, IdGenerator idGenerator,
                                      PermissionChecker permissionChecker, ChatInteractionProperties properties) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.permissionChecker = permissionChecker;
        this.properties = properties == null ? new ChatInteractionProperties() : properties;
    }

    public Optional<ChatInteractionRequest> findWaiting(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        return repository.findWaitingBySession(user.tenantId(), user.ownerUserId(), sessionId)
                .filter(request -> !expireIfNeeded(request, Instant.now()));
    }

    public void rejectIfWaiting(UserContext user, String sessionId) {
        findWaiting(user, sessionId)
                .ifPresent(request -> {
                    throw ChatInteractionUnavailableException.waitingRequired(sessionId, request.id());
                });
    }

    public ChatInteractionRequest prepareInteraction(ChatInteractionCreateContext context) {
        Instant now = Instant.now();
        String id = idGenerator.newId("interaction",
                IdGenerateContext.of(context.user().tenantId(), context.user().ownerUserId(), context.session().id()));
        return new ChatInteractionRequest(
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
                interactionType(context.requestPayload()),
                ChatInteractionStatus.WAITING,
                context.requestPayload(),
                Map.of(),
                properties.expiresAt(now),
                null,
                null,
                now,
                now
        );
    }

    public ChatInteractionRequest saveInteraction(ChatInteractionRequest request) {
        return repository.insert(request);
    }

    public ChatInteractionClaimResult claimInteractionResponse(ChatInteractionResponseCommand command, String continueRunId) {
        UserContext user = command.user();
        permissionChecker.checkChatPermission(user);
        Instant now = Instant.now();
        ChatInteractionRequest request = repository.findByOwnerAndId(user.tenantId(), user.ownerUserId(), command.interactionId())
                .orElseThrow(() -> new IllegalArgumentException("Interaction 请求不存在: " + command.interactionId()));
        if (expireIfNeeded(request, now)) {
            throw ChatInteractionUnavailableException.expired(command.interactionId());
        }
        if (!request.waiting()) {
            throw ChatInteractionUnavailableException.alreadyHandled(command.interactionId());
        }
        Map<String, Object> responsePayload = responsePayload(command, request.interactionType());
        boolean claimed = repository.claimInteractionResponse(new ChatInteractionRequestRepository.ChatInteractionClaimCommand(
                user.tenantId(), user.ownerUserId(), command.interactionId(), continueRunId, responsePayload, now));
        if (!claimed) {
            throw ChatInteractionUnavailableException.alreadyHandled(command.interactionId());
        }
        ChatInteractionRequest claimedRequest = repository.findByOwnerAndId(user.tenantId(), user.ownerUserId(),
                        command.interactionId())
                .orElse(request);
        return new ChatInteractionClaimResult(claimedRequest, responsePayload);
    }

    public void markAnswered(ChatInteractionRequest request) {
        if (request != null) {
            repository.markAnswered(request.tenantId(), request.userId(), request.id(), Instant.now());
        }
    }

    public void markWaiting(ChatInteractionRequest request) {
        if (request == null || request.continueRunId() == null || request.continueRunId().isBlank()) {
            return;
        }
        repository.markWaitingForRun(request.tenantId(), request.userId(), request.id(),
                request.continueRunId());
    }

    public int markWaitingForRun(String tenantId, String userId, String interactionId, String continueRunId) {
        if (tenantId == null || tenantId.isBlank() || userId == null || userId.isBlank()
                || interactionId == null || interactionId.isBlank()
                || continueRunId == null || continueRunId.isBlank()) {
            return 0;
        }
        return repository.markWaitingForRun(tenantId, userId, interactionId, continueRunId);
    }

    /**
     * 回收因进程退出或旧版本非原子终态提交而遗留的 RESPONDING claim。
     *
     * <p>候选只包含 continue run 已经 FAILED/CANCELLED 的 Interaction；每条更新仍携带
     * continueRunId 条件，避免候选查询后状态变化时覆盖新的续接 claim。</p>
     */
    public int reconcileTerminalContinuationClaims(int limit) {
        int released = 0;
        for (ChatInteractionRequestRepository.ContinuationReconcileCandidate candidate
                : findContinuationReconcileCandidates(limit)) {
            if (candidate.state() == ChatInteractionRequestRepository.ContinuationReconcileState.MISSING_EXECUTION) {
                continue;
            }
            released += releaseContinuationReconcileCandidate(candidate);
        }
        return released;
    }

    public java.util.List<ChatInteractionRequestRepository.ContinuationReconcileCandidate>
            findContinuationReconcileCandidates(int limit) {
        int normalizedLimit = Math.max(1, limit);
        Instant orphanBefore = Instant.now().minus(properties.normalizedRespondingOrphanGrace());
        try {
            return repository.findRespondingReconcileCandidates(orphanBefore, normalizedLimit);
        } catch (RuntimeException ex) {
            log.warn("Failed to query orphan Interaction claims for reconciliation. reason={}", ex.getMessage(), ex);
            return java.util.List.of();
        }
    }

    public int releaseContinuationReconcileCandidate(
            ChatInteractionRequestRepository.ContinuationReconcileCandidate candidate) {
        if (candidate == null || candidate.request() == null
                || candidate.state() == ChatInteractionRequestRepository.ContinuationReconcileState.MISSING_EXECUTION) {
            return 0;
        }
        ChatInteractionRequest request = candidate.request();
        try {
            return repository.markWaitingIfContinuationOrphaned(
                    request.tenantId(), request.userId(), request.id(), request.continueRunId(),
                    candidate.orphanBefore());
        } catch (RuntimeException ex) {
            log.warn("Failed to reconcile Interaction claim. interactionId={}, continueRunId={}, state={}, reason={}",
                    request.id(), request.continueRunId(), candidate.state(), ex.getMessage(), ex);
            return 0;
        }
    }

    public void cancelOpenBySession(UserContext user, String sessionId) {
        permissionChecker.checkChatPermission(user);
        repository.cancelOpenBySession(user.tenantId(), user.ownerUserId(), sessionId, Instant.now());
    }

    private boolean expireIfNeeded(ChatInteractionRequest request, Instant now) {
        if (request.expiredAt(now)) {
            repository.markExpired(request.tenantId(), request.userId(), request.id());
            return true;
        }
        return false;
    }

    private Map<String, Object> responsePayload(ChatInteractionResponseCommand command, ChatInteractionType interactionType) {
        validateResponse(command, interactionType);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approved", command.approved() == null ? Boolean.TRUE : command.approved());
        payload.put("scope", command.scope() == null || command.scope().isBlank() ? "once" : command.scope().trim());
        payload.put("questionnaireAnswers", command.questionnaireAnswers());
        payload.put("metadata", command.metadata());
        return Map.copyOf(payload);
    }

    private void validateResponse(ChatInteractionResponseCommand command, ChatInteractionType interactionType) {
        ChatInteractionType safeType = interactionType == null ? ChatInteractionType.AGENT_CLARIFICATION : interactionType;
        if (requiresExplicitApproval(safeType) && command.approved() == null) {
            throw new IllegalArgumentException("approved 不能为空");
        }
        if (requiresQuestionnaireAnswer(safeType) && (command.questionnaireAnswers() == null
                || command.questionnaireAnswers().isEmpty())) {
            throw new IllegalArgumentException("questionnaireAnswers 不能为空");
        }
    }

    private boolean requiresExplicitApproval(ChatInteractionType interactionType) {
        return interactionType == ChatInteractionType.DOMAIN_AGENT_SWITCH_CONFIRMATION
                || interactionType == ChatInteractionType.APPROVAL
                || interactionType == ChatInteractionType.CONFIRMATION;
    }

    private boolean requiresQuestionnaireAnswer(ChatInteractionType interactionType) {
        return interactionType == ChatInteractionType.INTENT_CLARIFICATION
                || interactionType == ChatInteractionType.AGENT_CLARIFICATION
                || interactionType == ChatInteractionType.CLARIFICATION;
    }

    private String approvalId(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("approval_id");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private ChatInteractionType interactionType(Map<String, Object> payload) {
        Object value = payload == null ? null : payload.get("interactionType");
        if (value != null && !String.valueOf(value).isBlank()) {
            try {
                return ChatInteractionType.valueOf(String.valueOf(value).trim());
            } catch (IllegalArgumentException ignored) {
                // 下游 payload 不是类型事实，未知值回落到 Agent 澄清，避免保存不可续接状态。
            }
        }
        return ChatInteractionType.AGENT_CLARIFICATION;
    }
}
