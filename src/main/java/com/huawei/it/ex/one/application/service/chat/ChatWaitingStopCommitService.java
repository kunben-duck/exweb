package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.memory.RouteMemoryRepository;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Optional;

/**
 * 等待态 run 的 stop 数据库提交器。
 *
 * <p>本服务只修改本地事实源。下游取消在事务提交后由 {@link ChatRunStopCoordinator} 执行，
 * 避免 WebSocket/HTTP 调用占用数据库锁。</p>
 */
@Service
public class ChatWaitingStopCommitService {
    private static final String USER_STOP = "USER_STOP";

    private final SessionApplicationService sessionService;
    private final ChatInteractionRequestRepository interactionRepository;
    private final ChatRunRepository runRepository;
    private final RuntimeBindingRepository bindingRepository;
    private final RuntimeBindingApplicationService bindingService;
    private final RouteMemoryRepository routeMemoryRepository;

    public ChatWaitingStopCommitService(
            SessionApplicationService sessionService,
            ChatInteractionRequestRepository interactionRepository,
            ChatRunRepository runRepository,
            RuntimeBindingRepository bindingRepository,
            RuntimeBindingApplicationService bindingService,
            RouteMemoryRepository routeMemoryRepository) {
        this.sessionService = sessionService;
        this.interactionRepository = interactionRepository;
        this.runRepository = runRepository;
        this.bindingRepository = bindingRepository;
        this.bindingService = bindingService;
        this.routeMemoryRepository = routeMemoryRepository;
    }

    /**
     * 原子取消请求 run 产生的等待态，并在存在 continuation run 时先将其置为 CANCELLING。
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public WaitingStopCommitResult cancelWaiting(UserContext user, ChatRun sourceRun, String reason) {
        validateSource(user, sourceRun);
        ChatSession session = sessionService.getSession(user, sourceRun.sessionId());
        sessionService.lockForMessageMutation(user.tenantId(), user.ownerUserId(), session);

        Optional<ChatInteractionRequest> candidate = interactionRepository.findLatestBySourceRun(
                user.tenantId(), user.ownerUserId(), sourceRun.sessionId(), sourceRun.id());
        if (candidate.isEmpty()) {
            return WaitingStopCommitResult.notFound(sourceRun);
        }

        ChatRun effectiveRun = lockAndMarkContinuationCancelling(user, candidate.get(), reason);
        ChatInteractionRequest interaction = interactionRepository.findByOwnerAndIdForUpdate(
                        user.tenantId(), user.ownerUserId(), candidate.get().id())
                .filter(request -> sourceRun.id().equals(request.sourceRunId()))
                .filter(request -> sourceRun.sessionId().equals(request.sessionId()))
                .orElse(null);
        if (interaction == null) {
            return WaitingStopCommitResult.notFound(sourceRun);
        }

        if (interaction.status() == ChatInteractionStatus.ANSWERED) {
            return WaitingStopCommitResult.answered(sourceRun, interaction, effectiveRun);
        }
        if (interaction.status() == ChatInteractionStatus.CANCELLED) {
            return WaitingStopCommitResult.alreadyCancelled(sourceRun, interaction, effectiveRun);
        }
        if (interaction.status() == ChatInteractionStatus.EXPIRED) {
            return WaitingStopCommitResult.notCancelled(sourceRun, interaction, effectiveRun);
        }

        Instant cancelledAt = Instant.now();
        int updated = cancelInteraction(interaction, cancelledAt);
        if (updated != 1) {
            throw new IllegalStateException("Interaction 等待态取消失败: " + interaction.id());
        }

        RuntimeBinding binding = cancelReferencedBinding(interaction);
        routeMemoryRepository.foldActiveClarifications(
                interaction.tenantId(), interaction.userId(), interaction.sessionId(), cancelledAt);
        WaitingRuntimeTarget runtimeTarget = runtimeTarget(effectiveRun, binding, sourceRun, interaction);
        return new WaitingStopCommitResult(
                sourceRun, interaction, effectiveRun, true, cancelledAt, binding, runtimeTarget);
    }

    private ChatRun lockAndMarkContinuationCancelling(
            UserContext user, ChatInteractionRequest interaction, String reason) {
        String continueRunId = normalize(interaction.continueRunId());
        if (continueRunId == null) {
            return null;
        }
        Optional<ChatRun> locked = runRepository.findByTenantIdAndUserIdAndIdForUpdate(
                user.tenantId(), user.ownerUserId(), continueRunId)
                .filter(run -> interaction.sessionId().equals(run.sessionId()));
        if (locked.isEmpty()) {
            return null;
        }
        ChatRun run = locked.get();
        if (run.status() == ChatRunStatus.CANCELLING) {
            return run;
        }
        if (run.status() != ChatRunStatus.RUNNING) {
            return null;
        }
        String effectiveReason = normalize(reason) == null ? USER_STOP : reason.trim();
        boolean marked = runRepository.tryMarkCancelling(new ChatRunRepository.StopClaim(
                run.id(), user.tenantId(), user.ownerUserId(), effectiveReason, Instant.now()));
        if (!marked) {
            return runRepository.findByTenantIdAndUserIdAndIdForUpdate(
                            user.tenantId(), user.ownerUserId(), run.id())
                    .filter(latest -> latest.status() == ChatRunStatus.CANCELLING)
                    .orElse(null);
        }
        return run.cancelling(effectiveReason);
    }

    private int cancelInteraction(ChatInteractionRequest interaction, Instant cancelledAt) {
        if (interaction.status() == ChatInteractionStatus.WAITING) {
            return interactionRepository.cancelWaitingById(
                    interaction.tenantId(), interaction.userId(), interaction.id(), cancelledAt);
        }
        if (interaction.status() == ChatInteractionStatus.RESPONDING) {
            return interactionRepository.cancelRespondingForRun(
                    interaction.tenantId(), interaction.userId(), interaction.id(),
                    interaction.continueRunId(), cancelledAt);
        }
        return 0;
    }

    private RuntimeBinding cancelReferencedBinding(ChatInteractionRequest interaction) {
        String bindingId = normalize(interaction.runtimeBindingId());
        if (bindingId == null) {
            return null;
        }
        RuntimeBinding binding = bindingRepository.findById(bindingId)
                .filter(current -> interaction.tenantId().equals(current.tenantId()))
                .filter(current -> interaction.userId().equals(current.userId()))
                .filter(current -> interaction.sessionId().equals(current.chatSessionId()))
                .orElse(null);
        if (binding == null) {
            return null;
        }
        if (binding.status() != RuntimeBindingStatus.ACTIVE) {
            return "domain-agent".equalsIgnoreCase(binding.provider()) ? binding : null;
        }
        boolean cancelled = bindingRepository.cancelActiveForInteraction(
                binding, interaction.sourceRunId(), interaction.continueRunId());
        if (!cancelled) {
            return null;
        }
        RuntimeBinding cancelledBinding = binding.withStatus(RuntimeBindingStatus.CANCELLED);
        synchronizeBindingCacheAfterCommit(cancelledBinding);
        return cancelledBinding;
    }

    private void synchronizeBindingCacheAfterCommit(RuntimeBinding binding) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    bindingService.synchronizeCache(binding);
                }
            });
            return;
        }
        bindingService.synchronizeCache(binding);
    }

    private WaitingRuntimeTarget runtimeTarget(
            ChatRun effectiveRun,
            RuntimeBinding binding,
            ChatRun sourceRun,
            ChatInteractionRequest interaction) {
        if (hasRuntime(effectiveRun)) {
            return WaitingRuntimeTarget.fromRun(effectiveRun);
        }
        if (binding != null && normalize(binding.provider()) != null) {
            String targetId = metadataText(binding, "domainAgentId");
            // continuation run 即使已经终态，仍应作为下游幂等取消的真实任务标识。
            String continueRunId = normalize(interaction == null ? null : interaction.continueRunId());
            String runId = effectiveRun != null
                    ? effectiveRun.id()
                    : continueRunId == null ? sourceRun.id() : continueRunId;
            return new WaitingRuntimeTarget(
                    runId,
                    sourceRun.sessionId(),
                    binding.provider(),
                    binding.runtimeSessionId(),
                    targetId,
                    sourceRun.routeType());
        }
        return hasRuntime(sourceRun) ? WaitingRuntimeTarget.fromRun(sourceRun) : null;
    }

    private boolean hasRuntime(ChatRun run) {
        String provider = run == null ? null : normalize(run.runtimeProvider());
        return "relay".equalsIgnoreCase(provider) || "domain-agent".equalsIgnoreCase(provider);
    }

    private String metadataText(RuntimeBinding binding, String key) {
        Object value = binding == null || binding.metadata() == null ? null : binding.metadata().get(key);
        return value == null ? null : normalize(String.valueOf(value));
    }

    private void validateSource(UserContext user, ChatRun sourceRun) {
        if (user == null || sourceRun == null || sourceRun.status() != ChatRunStatus.WAITING_USER) {
            throw new IllegalArgumentException("等待态 stop 参数不完整");
        }
        if (!user.tenantId().equals(sourceRun.tenantId())
                || !user.ownerUserId().equals(sourceRun.userId())) {
            throw new SecurityException("run 不属于当前用户");
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 等待态 stop 事务提交结果。 */
    public record WaitingStopCommitResult(
            ChatRun sourceRun,
            ChatInteractionRequest interaction,
            ChatRun effectiveRun,
            boolean interactionCancelled,
            Instant interactionCancelledAt,
            RuntimeBinding cancelledBinding,
            WaitingRuntimeTarget runtimeTarget) {
        static WaitingStopCommitResult notFound(ChatRun sourceRun) {
            return new WaitingStopCommitResult(sourceRun, null, null, false, null, null, null);
        }

        static WaitingStopCommitResult answered(
                ChatRun sourceRun, ChatInteractionRequest interaction, ChatRun effectiveRun) {
            return new WaitingStopCommitResult(
                    sourceRun, interaction, effectiveRun, false, null, null,
                    effectiveRun == null ? null : WaitingRuntimeTarget.fromRun(effectiveRun));
        }

        static WaitingStopCommitResult notCancelled(
                ChatRun sourceRun, ChatInteractionRequest interaction, ChatRun effectiveRun) {
            return new WaitingStopCommitResult(sourceRun, interaction, effectiveRun, false, null, null, null);
        }

        static WaitingStopCommitResult alreadyCancelled(
                ChatRun sourceRun, ChatInteractionRequest interaction, ChatRun effectiveRun) {
            return new WaitingStopCommitResult(
                    sourceRun, interaction, effectiveRun, false, interaction.cancelledAt(), null, null);
        }

    }

    /** 事务外实际下游取消所需的可信目标快照。 */
    public record WaitingRuntimeTarget(
            String runId,
            String sessionId,
            String provider,
            String runtimeSessionId,
            String runtimeTargetId,
            String routeType) {
        static WaitingRuntimeTarget fromRun(ChatRun run) {
            return new WaitingRuntimeTarget(
                    run.id(), run.sessionId(), run.runtimeProvider(), run.runtimeSessionId(),
                    run.agentCode(), run.routeType());
        }
    }
}
