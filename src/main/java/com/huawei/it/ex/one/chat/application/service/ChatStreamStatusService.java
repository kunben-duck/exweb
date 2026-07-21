package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.runtime.application.model.RuntimeProviders;

import com.huawei.it.ex.one.chat.application.repository.ChatEventStore;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatStreamStatus;
import com.huawei.it.ex.one.chat.domain.ChatStreamTopics;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Assembles the existing stream-status view without owning run lifecycle mutations. */
@Service
public class ChatStreamStatusService {
    private final ChatEventStore eventStore;
    private final ChatRunLeaseApplicationService leaseService;
    private final ObjectProvider<ChatRunRecoveryOrchestrator> recoveryOrchestratorProvider;
    private final ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider;
    private final ObjectProvider<RuntimeBindingService> runtimeBindingServiceProvider;

    @Autowired
    public ChatStreamStatusService(
            ChatEventStore eventStore,
            ChatRunLeaseApplicationService leaseService,
            ObjectProvider<ChatRunRecoveryOrchestrator> recoveryOrchestratorProvider,
            ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider,
            ObjectProvider<RuntimeBindingService> runtimeBindingServiceProvider) {
        this.eventStore = eventStore;
        this.leaseService = leaseService;
        this.recoveryOrchestratorProvider = recoveryOrchestratorProvider;
        this.interactionServiceProvider = interactionServiceProvider;
        this.runtimeBindingServiceProvider = runtimeBindingServiceProvider;
    }

    ChatStreamStatusService(ChatEventStore eventStore) {
        this(eventStore, null, null, null, null);
    }

    public ChatStreamStatus streamStatus(
            UserContext user,
            String sessionId,
            Supplier<Optional<ChatRun>> activeRunLookup) {
        long latestSeq = eventStore.findLatestSeqByOwnerAndSession(
                user.tenantId(), user.ownerUserId(), sessionId);
        Optional<ChatRun> active = activeRunLookup.get();
        if (active.isPresent() && leaseService != null && leaseService.isLeaseExpired(active.get().id())) {
            ChatRunRecoveryOrchestrator orchestrator = recoveryOrchestratorProvider == null
                    ? null
                    : recoveryOrchestratorProvider.getIfAvailable();
            if (orchestrator != null) {
                orchestrator.recoverExpiredRun(active.get().id());
                latestSeq = eventStore.findLatestSeqByOwnerAndSession(
                        user.tenantId(), user.ownerUserId(), sessionId);
                active = activeRunLookup.get();
            }
        }
        long currentLatestSeq = latestSeq;
        BindingSummary bindingSummary = bindingSummary(user, sessionId);
        return active
                .map(run -> new ChatStreamStatus(sessionId, currentLatestSeq, run.id(), run.status(),
                        ChatStreamTopics.runTopic(run.id()), run.firstSeq(), run.lastSeq(), run.cancellable(),
                        false, null, null, null, null,
                        bindingSummary.provider(), bindingSummary.targetType(), bindingSummary.targetId(),
                        bindingSummary.intentCode(), bindingSummary.intentName(), bindingSummary.routeSource(),
                        bindingSummary.updatedAt()))
                .orElseGet(() -> waitingStatus(user, sessionId, currentLatestSeq, bindingSummary));
    }

    private ChatStreamStatus waitingStatus(UserContext user, String sessionId, long latestSeq,
                                           BindingSummary bindingSummary) {
        ChatInteractionApplicationService interactionService = interactionServiceProvider == null
                ? null
                : interactionServiceProvider.getIfAvailable();
        if (interactionService == null) {
            return emptyStatus(sessionId, latestSeq, bindingSummary);
        }
        return interactionService.findWaiting(user, sessionId)
                .map(request -> new ChatStreamStatus(sessionId, latestSeq, null, null, null, null, null,
                        false, true, request.id(), request.interactionType().name(),
                        request.assistantMessageId(), request.expiresAt(),
                        bindingSummary.provider(), bindingSummary.targetType(), bindingSummary.targetId(),
                        bindingSummary.intentCode(), bindingSummary.intentName(), bindingSummary.routeSource(),
                        bindingSummary.updatedAt()))
                .orElseGet(() -> emptyStatus(sessionId, latestSeq, bindingSummary));
    }

    private ChatStreamStatus emptyStatus(String sessionId, long latestSeq, BindingSummary bindingSummary) {
        return new ChatStreamStatus(sessionId, latestSeq, null, null, null, null, null,
                false, false, null, null, null, null,
                bindingSummary.provider(), bindingSummary.targetType(), bindingSummary.targetId(),
                bindingSummary.intentCode(), bindingSummary.intentName(), bindingSummary.routeSource(),
                bindingSummary.updatedAt());
    }

    private BindingSummary bindingSummary(UserContext user, String sessionId) {
        RuntimeBindingService runtimeBindingService = runtimeBindingServiceProvider == null
                ? null
                : runtimeBindingServiceProvider.getIfAvailable();
        if (runtimeBindingService == null) {
            return BindingSummary.empty();
        }
        return runtimeBindingService.findActiveBySession(user.tenantId(), user.ownerUserId(), sessionId)
                .map(this::toBindingSummary)
                .orElseGet(BindingSummary::empty);
    }

    private BindingSummary toBindingSummary(RuntimeBinding binding) {
        Map<String, Object> metadata = binding.metadata();
        String targetType = RuntimeProviders.DOMAIN_AGENT.equals(binding.provider())
                ? "DOMAIN_AGENT"
                : "AGENT_RUNTIME";
        return new BindingSummary(
                binding.provider(),
                targetType,
                stringValue(metadata.get("domainAgentId")),
                stringValue(metadata.get("intentCode")),
                stringValue(metadata.get("intentName")),
                stringValue(metadata.get("routeSource")),
                binding.updatedAt());
    }

    private String stringValue(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private record BindingSummary(String provider, String targetType, String targetId, String intentCode,
                                  String intentName, String routeSource, Instant updatedAt) {
        private static BindingSummary empty() {
            return new BindingSummary(null, null, null, null, null, null, null);
        }
    }
}
