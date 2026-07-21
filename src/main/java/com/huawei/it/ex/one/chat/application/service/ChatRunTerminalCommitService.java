package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import com.huawei.it.ex.one.chat.application.model.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatMessage;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunExecution;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingStatus;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat run 终态数据库提交器。
 *
 * <p>本服务只做本地事实源写入，不发布 Redis/WebSocket，也不订阅 Reactor 流。这样
 * {@code run.waiting_user} 这类前端依赖多张表的终态可以在短事务内一次提交成功，避免出现
 * event 已可恢复但 Interaction 请求或 assistant part 缺失的半截状态。</p>
 */
@Service
public class ChatRunTerminalCommitService {
    private static final String DOMAIN_AGENT_PROVIDER = "domain-agent";

    private final ChatStreamApplicationService chatStreamService;
    private final ChatRunRepository runRepository;
    private final RuntimeBindingService runtimeBindingService;
    private final ChatRunTerminalMessageWriter messageWriter;
    private final ChatRunTerminalInteractionSupport interactionSupport;
    private final ChatRunTerminalStateObserver stateObserver;

    public ChatRunTerminalCommitService(ChatStreamApplicationService chatStreamService,
                                        SessionApplicationService sessionService,
                                        ChatRunRepository runRepository,
                                        ChatRunLeaseApplicationService runLeaseService,
                                        RuntimeBindingService runtimeBindingService,
                                        ChatInteractionApplicationService chatInteractionService) {
        this.chatStreamService = chatStreamService;
        this.runRepository = runRepository;
        this.runtimeBindingService = runtimeBindingService;
        this.messageWriter = new ChatRunTerminalMessageWriter(sessionService, runRepository);
        this.interactionSupport = new ChatRunTerminalInteractionSupport(chatInteractionService);
        this.stateObserver = new ChatRunTerminalStateObserver(
                runRepository,
                runLeaseService,
                runtimeBindingService);
    }

    /**
     * 原子提交自动 DomainAgent 拒答事实及 binding 失效状态。
     *
     * <p>事件写入先获取 run/execution guard，再更新 RuntimeBinding，保持与 owner 终态一致的
     * {@code run -> runtime_binding} 锁顺序。Redis 与实时发布必须在事务提交后由调用方处理。</p>
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commitDomainAgentRefusal(DomainAgentRefusalCommitCommand command) {
        if (command == null || command.event() == null || command.executionClaim() == null) {
            throw new IllegalArgumentException("DomainAgent 拒答提交参数不能为空");
        }
        RuntimeBinding binding = command.binding();
        if (binding == null
                || !DOMAIN_AGENT_PROVIDER.equals(binding.provider())
                || binding.status() != RuntimeBindingStatus.ACTIVE) {
            throw new IllegalStateException("DomainAgent 拒答提交缺少 ACTIVE binding");
        }
        if (!Objects.equals(command.event().sessionId(), binding.chatSessionId())) {
            throw new IllegalStateException("DomainAgent 拒答 event 与 binding 会话不一致");
        }
        ChatEvent stored = chatStreamService.appendWithExecutionGuard(command.event(), command.executionClaim());
        RuntimeBinding cancelled = runtimeBindingService.cancelForRefusalInCurrentTransaction(
                binding, command.rejectCode());
        return new CommitResult(stored, cancelled);
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commitCompleted(CompletedCommitCommand command) {
        messageWriter.lockOwnerTerminalSession(command.context());
        fenceOwnerTerminalCommit(command.context());
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        ChatMessage savedAssistant = messageWriter.saveCompletedAssistant(command);
        messageWriter.advanceLatestMessageSeq(command.context(), stored);
        messageWriter.bindAssistantMessage(stored.runId(), savedAssistant.id());
        RuntimeBinding binding = stateObserver.completeBinding(command.context(), savedAssistant.id());
        if (interactionSupport.reusable(command.context())) {
            interactionSupport.markAnswered(command.context());
        }
        stateObserver.observeRun(stored);
        stateObserver.markExecutionTerminal(stored);
        binding = stateObserver.observeRuntimeBindingEvent(binding, stored);
        return new CommitResult(stored, binding);
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commitWaitingUser(WaitingUserCommitCommand command) {
        messageWriter.lockOwnerTerminalSession(command.context());
        fenceOwnerTerminalCommit(command.context());
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        ChatMessage savedAssistant = messageWriter.saveWaitingAssistant(command);
        messageWriter.advanceLatestMessageSeq(command.context(), stored);
        messageWriter.bindAssistantMessage(stored.runId(), savedAssistant.id());
        RuntimeBinding binding = stateObserver.refreshBinding(command.context(), savedAssistant.id());
        if (interactionSupport.reusable(command.context())) {
            interactionSupport.markAnswered(command.context());
        }
        interactionSupport.saveInteraction(command);
        stateObserver.observeRun(stored);
        stateObserver.markExecutionTerminal(stored);
        binding = stateObserver.observeRuntimeBindingEvent(binding, stored);
        return new CommitResult(stored, binding);
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commitTerminalOnly(TerminalOnlyCommitCommand command) {
        fenceOwnerTerminalCommit(command.context());
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        stateObserver.observeRun(stored);
        if (interactionSupport.reusable(command.context())
                && ("run.failed".equals(stored.type()) || "run.cancelled".equals(stored.type()))) {
            interactionSupport.markWaiting(command.context());
        }
        stateObserver.markExecutionTerminal(stored);
        RuntimeBinding binding = stateObserver.invalidateUnavailableRuntimeSession(
                command.context().bindingRef().get(), stored);
        binding = stateObserver.observeRuntimeBindingEvent(binding, stored);
        return new CommitResult(stored, binding);
    }

    /**
     * 提交由 stop 或 watchdog 产生的非执行 owner 终态。
     *
     * <p>这类终态不能使用普通 execution guard：stop 可能由另一实例发起，watchdog 的 execution
     * 已进入 RECOVERING。需要保存 partial assistant 时先锁 session，再通过 run 条件更新抢占写入权；
     * 不写消息的 recovery 仍直接竞争 run。实时发布由调用方在本方法成功返回后执行。</p>
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public ExternalTerminalCommitResult commitExternalTerminal(ExternalTerminalCommitCommand command) {
        if (command == null || command.event() == null || command.run() == null) {
            throw new IllegalArgumentException("外部终态提交参数不能为空");
        }
        if (!"run.cancelled".equals(command.event().type()) && !"run.failed".equals(command.event().type())) {
            throw new IllegalArgumentException("外部终态只支持 run.cancelled/run.failed");
        }
        messageWriter.lockExternalPartialAssistantSession(command);
        ChatRunStatus terminalStatus = "run.cancelled".equals(command.event().type())
                ? ChatRunStatus.CANCELLED
                : ChatRunStatus.FAILED;
        java.time.Instant finishedAt = java.time.Instant.now();
        boolean claimed = runRepository.tryClaimExternalTerminal(
                new ChatRunRepository.ExternalTerminalClaim(
                        command.run().id(),
                        command.run().tenantId(),
                        command.run().userId(),
                        command.run().sessionId(),
                        terminalStatus,
                        command.run().cancelReason(),
                        finishedAt,
                        command.guard(),
                        command.recoveredByInstanceId(),
                        command.fencingToken(),
                        command.interactionId(),
                        command.orphanBefore()
                ));
        if (!claimed) {
            ChatRun latest = runRepository.findById(command.run().id()).orElse(command.run());
            return new ExternalTerminalCommitResult(null, latest, false);
        }
        messageWriter.persistExternalPartialAssistant(command);
        ChatEvent stored = chatStreamService.appendWithoutPublish(command.event());
        ChatRun committedRun = runRepository.finalizeExternalTerminal(
                new ChatRunRepository.ExternalTerminalFinalize(
                        command.run().id(),
                        command.run().tenantId(),
                        command.run().userId(),
                        command.run().sessionId(),
                        terminalStatus,
                        stored.sequence(),
                        command.run().cancelReason(),
                        finishedAt
                ));
        interactionSupport.releaseContinuationClaim(committedRun, command.interactionId());
        stateObserver.markExecutionTerminal(stored);
        return new ExternalTerminalCommitResult(stored, committedRun, true);
    }

    /**
     * 幂等修复终态 continuation run 遗留的 Interaction claim。
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public int reconcileTerminalInteraction(ChatRun run) {
        if (run == null || (run.status() != ChatRunStatus.CANCELLED && run.status() != ChatRunStatus.FAILED)) {
            return 0;
        }
        return interactionSupport.releaseContinuationClaim(run);
    }

    private ChatEvent append(ChatEvent event, TerminalCommitContext context) {
        return chatStreamService.appendWithExecutionGuard(event, context.executionClaim());
    }

    private void fenceOwnerTerminalCommit(TerminalCommitContext context) {
        ChatRunTerminalMessageWriter.validateOwnerTerminalContext(context);
        boolean fenced = runRepository.tryFenceOwnerTerminalCommit(
                new ChatRunRepository.OwnerTerminalFence(
                        context.runId(),
                        context.user().tenantId(),
                        context.user().ownerUserId(),
                        context.session().id(),
                        context.executionClaim()
                ));
        if (!fenced) {
            throw new ChatEventAppendRejectedException(
                    "owner 终态提交被 run/execution 栅栏拒绝: runId=" + context.runId());
        }
    }

    public record TerminalCommitContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            ChatInteractionRequest continuationInteractionRequest
    ) {
    }

    public record MessageTarget(boolean messageReady, String assistantMessageId) {
    }

    public record CompletedCommitCommand(
            ChatEvent event,
            TerminalCommitContext context,
            MessageTarget target
    ) {
    }

    public record WaitingUserCommitCommand(
            ChatEvent event,
            TerminalCommitContext context,
            MessageTarget target,
            ChatInteractionRequest waitingRequest
    ) {
    }

    public record TerminalOnlyCommitCommand(
            ChatEvent event,
            TerminalCommitContext context
    ) {
    }

    public record DomainAgentRefusalCommitCommand(
            ChatEvent event,
            RunExecutionClaim executionClaim,
            RuntimeBinding binding,
            String rejectCode
    ) {
    }

    public record ExternalTerminalCommitCommand(
            ChatEvent event,
            ChatRun run,
            ChatRunRepository.ExternalTerminalGuard guard,
            String recoveredByInstanceId,
            Long fencingToken,
            String interactionId,
            java.time.Instant orphanBefore,
            AssistantMessageSaveCommand partialAssistant
    ) {
        public ExternalTerminalCommitCommand(ChatEvent event, ChatRun run) {
            this(event, run, ChatRunRepository.ExternalTerminalGuard.NONE,
                    null, null, null, null, null);
        }

        public static ExternalTerminalCommitCommand stop(ChatEvent event, ChatRun run,
                                                         AssistantMessageSaveCommand partialAssistant) {
            return new ExternalTerminalCommitCommand(
                    event,
                    run,
                    ChatRunRepository.ExternalTerminalGuard.NONE,
                    null,
                    null,
                    null,
                    null,
                    partialAssistant
            );
        }

        public static ExternalTerminalCommitCommand recovery(ChatEvent event, ChatRun run,
                                                             ChatRunExecution execution, String instanceId) {
            return new ExternalTerminalCommitCommand(
                    event,
                    run,
                    ChatRunRepository.ExternalTerminalGuard.RECOVERY,
                    instanceId,
                    execution == null ? null : execution.fencingToken(),
                    null,
                    null,
                    null
            );
        }

        public static ExternalTerminalCommitCommand orphanInteraction(ChatEvent event, ChatRun run,
                                                                      String interactionId,
                                                                      java.time.Instant orphanBefore) {
            return new ExternalTerminalCommitCommand(
                    event,
                    run,
                    ChatRunRepository.ExternalTerminalGuard.ORPHAN_INTERACTION,
                    null,
                    null,
                    interactionId,
                    orphanBefore,
                    null
            );
        }

        public static ExternalTerminalCommitCommand executionInitFailure(ChatEvent event, ChatRun run,
                                                                         String interactionId) {
            return new ExternalTerminalCommitCommand(
                    event,
                    run,
                    ChatRunRepository.ExternalTerminalGuard.EXECUTION_INIT_FAILURE,
                    null,
                    null,
                    interactionId,
                    null,
                    null
            );
        }

        public static ExternalTerminalCommitCommand orphanRunInitialization(ChatEvent event, ChatRun run,
                                                                             java.time.Instant orphanBefore) {
            return new ExternalTerminalCommitCommand(
                    event,
                    run,
                    ChatRunRepository.ExternalTerminalGuard.ORPHAN_RUN_INIT,
                    null,
                    null,
                    null,
                    orphanBefore,
                    null
            );
        }

        public static ExternalTerminalCommitCommand firstEventTimeout(ChatEvent event, ChatRun run,
                                                                       String interactionId,
                                                                       RunExecutionClaim executionClaim) {
            return new ExternalTerminalCommitCommand(
                    event,
                    run,
                    ChatRunRepository.ExternalTerminalGuard.FIRST_EVENT_TIMEOUT,
                    executionClaim == null ? null : executionClaim.ownerInstanceId(),
                    executionClaim == null ? null : executionClaim.fencingToken(),
                    interactionId,
                    null,
                    null
            );
        }
    }

    public record ExternalTerminalCommitResult(ChatEvent event, ChatRun run, boolean committed) {
    }

    public record CommitResult(ChatEvent event, RuntimeBinding binding) {
    }
}
