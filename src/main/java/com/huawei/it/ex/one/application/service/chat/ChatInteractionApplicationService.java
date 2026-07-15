package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Interaction 等待用户输入应用服务。
 *
 * <p>本服务只管理跨请求等待态和续接事实，不解释 Relay questionnaire 的展示结构；
 * 展示快照仍通过 message parts 返回给前端。</p>
 */
@Service
public class ChatInteractionApplicationService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatInteractionApplicationService.class);

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
        return claimInteractionResponse(command, continueRunId, request -> { });
    }

    ChatInteractionClaimResult claimInteractionResponse(ChatInteractionResponseCommand command,
                                                         String continueRunId,
                                                         Consumer<ChatInteractionRequest> preClaimValidator) {
        return claimPreparedInteractionResponse(command, continueRunId, request -> {
            Map<String, Object> responsePayload = prepareResponsePayload(command, request.interactionType(), null);
            if (preClaimValidator != null) {
                preClaimValidator.accept(request);
            }
            return responsePayload;
        });
    }

    ChatInteractionClaimResult claimPreparedInteractionResponse(
            ChatInteractionResponseCommand command,
            String continueRunId,
            Function<ChatInteractionRequest, Map<String, Object>> preClaimResponsePreparer) {
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
        if (preClaimResponsePreparer == null) {
            throw new IllegalArgumentException("Interaction response preparer 不能为空");
        }
        Map<String, Object> responsePayload = preClaimResponsePreparer.apply(request);
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

    public int markAnsweredForRun(ChatInteractionRequest request, String continueRunId) {
        if (request == null || continueRunId == null || continueRunId.isBlank()) {
            return 0;
        }
        return repository.markAnsweredForRun(request.tenantId(), request.userId(), request.id(),
                continueRunId, Instant.now());
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

    /**
     * 历史累计附件失效时取消尚未被 claim 的 Interaction。
     *
     * <p>本方法只提交状态变更，不在事务内抛出最终业务异常，避免取消操作随异常回滚。</p>
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public boolean cancelWaitingForUnavailableAttachment(ChatInteractionRequest request) {
        if (request == null) {
            return false;
        }
        return repository.cancelWaitingById(
                request.tenantId(), request.userId(), request.id(), Instant.now()) == 1;
    }

    private boolean expireIfNeeded(ChatInteractionRequest request, Instant now) {
        if (request.expiredAt(now)) {
            repository.markExpired(request.tenantId(), request.userId(), request.id());
            return true;
        }
        return false;
    }

    Map<String, Object> prepareResponsePayload(ChatInteractionResponseCommand command,
                                               ChatInteractionType interactionType,
                                               String intentAnswerOverride) {
        String intentAnswer = interactionType == ChatInteractionType.INTENT_CLARIFICATION
                ? firstNonBlank(intentAnswerOverride, optionalIntentClarificationAnswer(command.questionnaireAnswers()))
                : null;
        validateResponse(command, interactionType, intentAnswer);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("approved", command.approved() == null ? Boolean.TRUE : command.approved());
        payload.put("scope", command.scope() == null || command.scope().isBlank() ? "once" : command.scope().trim());
        payload.put("questionnaireAnswers", command.questionnaireAnswers());
        if (interactionType == ChatInteractionType.INTENT_CLARIFICATION) {
            payload.put("answerText", intentAnswer);
        }
        payload.put("metadata", command.metadata());
        return Map.copyOf(payload);
    }

    private void validateResponse(ChatInteractionResponseCommand command, ChatInteractionType interactionType,
                                  String intentAnswer) {
        ChatInteractionType safeType = interactionType == null ? ChatInteractionType.AGENT_CLARIFICATION : interactionType;
        if (requiresExplicitApproval(safeType) && command.approved() == null) {
            throw new IllegalArgumentException("approved 不能为空");
        }
        if (requiresQuestionnaireAnswer(safeType) && safeType != ChatInteractionType.INTENT_CLARIFICATION
                && (command.questionnaireAnswers() == null
                || command.questionnaireAnswers().isEmpty())) {
            throw new IllegalArgumentException("questionnaireAnswers 不能为空");
        }
        if (safeType == ChatInteractionType.INTENT_CLARIFICATION) {
            if (intentAnswer == null || intentAnswer.isBlank()) {
                if ((command.questionnaireAnswers() == null || command.questionnaireAnswers().isEmpty())
                        && command.attachments().isEmpty()) {
                    throw new IllegalArgumentException("questionnaireAnswers 或 attachments 不能为空");
                }
                if (command.attachments().isEmpty()) {
                    throw new IllegalArgumentException("questionnaireAnswers 至少包含一个非空答案");
                }
                throw new IllegalArgumentException("意图澄清附件尚未完成服务端校验");
            }
        }
    }

    String optionalIntentClarificationAnswer(Map<String, Object> answers) {
        java.util.List<Map.Entry<String, String>> normalized = answers == null
                ? java.util.List.of()
                : answers.entrySet().stream()
                .map(entry -> Map.entry(
                        entry.getKey() == null ? "" : entry.getKey().trim(),
                        entry.getValue() == null ? "" : String.valueOf(entry.getValue()).trim()))
                .filter(entry -> !entry.getValue().isBlank())
                .sorted(Map.Entry.comparingByKey())
                .toList();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.size() == 1) {
            return normalized.getFirst().getValue();
        }
        return normalized.stream()
                .map(entry -> (entry.getKey().isBlank() ? "问题" : entry.getKey()) + "：" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null || second.isBlank() ? null : second.trim();
    }

    private boolean requiresExplicitApproval(ChatInteractionType interactionType) {
        return interactionType == ChatInteractionType.ROUTE_SWITCH_CONFIRMATION
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
