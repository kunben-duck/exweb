package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeInteractionDispatchState;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingExpirationPolicy;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Chat run 终态数据库提交器。
 *
 * <p>本服务只做本地事实源写入，不发布 Redis/WebSocket，也不订阅 Reactor 流。这样
 * {@code run.waiting_user} 这类前端依赖多张表的终态可以在短事务内一次提交成功，避免出现
 * event 已可恢复但 Interaction 请求或 assistant part 缺失的半截状态。</p>
 */
@Service
public class ChatRunTerminalCommitService {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunTerminalCommitService.class);
    private static final String WAITING_ASSISTANT_METADATA = "{\"finishReason\":\"WAITING_USER\"}";
    private static final String DOMAIN_AGENT_PROVIDER = "domain-agent";
    private static final String RELAY_PROVIDER = "relay";
    private static final String RUNTIME_SESSION_ESTABLISHED = "runtimeSessionEstablished";
    private static final String RUNTIME_SESSION_UNAVAILABLE = "RUNTIME_SESSION_UNAVAILABLE";
    private static final String INTERACTION_ID_METADATA = "interactionId";
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";

    private final ChatStreamApplicationService chatStreamService;
    private final SessionApplicationService sessionService;
    private final ChatRunRepository runRepository;
    private final ChatRunLeaseApplicationService runLeaseService;
    private final RuntimeBindingRepository runtimeBindingRepository;
    private final ChatInteractionApplicationService chatInteractionService;
    private final Duration runtimeBindingTtl;
    private final MessageSkillMetadata messageSkillMetadata;
    private final ObjectMapper objectMapper;

    @Autowired
    public ChatRunTerminalCommitService(ChatStreamApplicationService chatStreamService,
                                        SessionApplicationService sessionService,
                                        ChatRunRepository runRepository,
                                        ChatRunLeaseApplicationService runLeaseService,
                                        RuntimeBindingRepository runtimeBindingRepository,
                                        ChatInteractionApplicationService chatInteractionService,
                                        @Value("${financeex.runtime-binding.ttl:0s}") Duration runtimeBindingTtl,
                                        ObjectMapper objectMapper) {
        this.chatStreamService = chatStreamService;
        this.sessionService = sessionService;
        this.runRepository = runRepository;
        this.runLeaseService = runLeaseService;
        this.runtimeBindingRepository = runtimeBindingRepository;
        this.chatInteractionService = chatInteractionService;
        this.runtimeBindingTtl = RuntimeBindingExpirationPolicy.normalize(runtimeBindingTtl);
        this.messageSkillMetadata = new MessageSkillMetadata(objectMapper);
        this.objectMapper = objectMapper;
    }

    public ChatRunTerminalCommitService(ChatStreamApplicationService chatStreamService,
                                        SessionApplicationService sessionService,
                                        ChatRunRepository runRepository,
                                        ChatRunLeaseApplicationService runLeaseService,
                                        RuntimeBindingRepository runtimeBindingRepository,
                                        ChatInteractionApplicationService chatInteractionService,
                                        Duration runtimeBindingTtl) {
        this(chatStreamService, sessionService, runRepository, runLeaseService,
                runtimeBindingRepository, chatInteractionService, runtimeBindingTtl, new ObjectMapper());
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
        if (binding == null || !DOMAIN_AGENT_PROVIDER.equals(binding.provider())
                || binding.status() != RuntimeBindingStatus.ACTIVE) {
            throw new IllegalStateException("DomainAgent 拒答提交缺少 ACTIVE binding");
        }
        if (!Objects.equals(command.event().sessionId(), binding.chatSessionId())) {
            throw new IllegalStateException("DomainAgent 拒答 event 与 binding 会话不一致");
        }
        ChatEvent stored = chatStreamService.appendWithExecutionGuard(command.event(), command.executionClaim());
        Map<String, Object> metadata = new LinkedHashMap<>(binding.metadata());
        if (command.rejectCode() != null && !command.rejectCode().isBlank()) {
            metadata.put("lastRejectCode", command.rejectCode());
        }
        RuntimeBinding cancelled = runtimeBindingRepository.save(
                binding.withMetadata(metadata).withStatus(RuntimeBindingStatus.CANCELLED));
        return new CommitResult(stored, cancelled);
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commitCompleted(CompletedCommitCommand command) {
        lockOwnerTerminalSession(command.context());
        fenceOwnerTerminalCommit(command.context());
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        ChatMessage savedAssistant = saveCompletedAssistant(
                command, command.context().assistant().messageSkill().current());
        advanceLatestMessageSeq(command.context(), stored);
        bindAssistantMessage(stored.runId(), savedAssistant.id());
        RuntimeBinding binding = completeBinding(command.context(), savedAssistant.id());
        if (reusableInteraction(command.context())) {
            chatInteractionService.markAnswered(command.context().continuationInteractionRequest());
        }
        observeRun(stored);
        markExecutionTerminal(stored);
        binding = observeRuntimeBindingEvent(binding, stored);
        return new CommitResult(stored, binding);
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commitWaitingUser(WaitingUserCommitCommand command) {
        lockOwnerTerminalSession(command.context());
        fenceOwnerTerminalCommit(command.context());
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        ChatMessage savedAssistant = saveWaitingAssistant(
                command, command.context().assistant().messageSkill().current());
        advanceLatestMessageSeq(command.context(), stored);
        bindAssistantMessage(stored.runId(), savedAssistant.id());
        RuntimeBinding binding = refreshBinding(command.context(), savedAssistant.id());
        if (reusableInteraction(command.context())) {
            chatInteractionService.markAnswered(command.context().continuationInteractionRequest());
        }
        chatInteractionService.saveInteraction(command.waitingRequest());
        observeRun(stored);
        markExecutionTerminal(stored);
        binding = observeRuntimeBindingEvent(binding, stored);
        return new CommitResult(stored, binding);
    }

    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public CommitResult commitTerminalOnly(TerminalOnlyCommitCommand command) {
        fenceOwnerTerminalCommit(command.context());
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        observeRun(stored);
        boolean cancelFailedRelayInteraction = cancelFailedRelayInteraction(command.context(), stored);
        if (cancelFailedRelayInteraction) {
            ChatInteractionRequest request = command.context().continuationInteractionRequest();
            chatInteractionService.cancelRespondingForRun(
                    request.tenantId(), request.userId(), request.id(), command.context().runId(),
                    java.time.Instant.now());
        } else if (reusableInteraction(command.context()) && "run.failed".equals(stored.type())) {
            chatInteractionService.markWaiting(command.context().continuationInteractionRequest());
        } else if (reusableInteraction(command.context()) && "run.cancelled".equals(stored.type())) {
            ChatInteractionRequest request = command.context().continuationInteractionRequest();
            chatInteractionService.cancelRespondingForRun(
                    request.tenantId(), request.userId(), request.id(), command.context().runId(),
                    java.time.Instant.now());
        }
        RuntimeBinding binding = command.context().bindingRef().get();
        if (cancelFailedRelayInteraction) {
            binding = cancelFailedRelayInteractionBinding(binding, command.context());
        }
        markExecutionTerminal(stored);
        if (!cancelFailedRelayInteraction) {
            binding = invalidateUnavailableRuntimeSession(binding, stored);
        }
        binding = observeRuntimeBindingEvent(binding, stored);
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
        lockExternalPartialAssistantSession(command);
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
        ChatRun latestRun = runRepository.findById(command.run().id())
                .orElseThrow(() -> new IllegalStateException(
                        "外部终态抢占成功后run回读失败: " + command.run().id()));
        String skillId = MessageSkillContext.runSkillId(latestRun.metadata());
        if (DomainAgentAsyncTaskMetadata.isAsyncRunning(latestRun)) {
            persistAsyncTerminalAssistant(latestRun, terminalStatus);
            latestRun = runRepository.save(latestRun.withMetadataSnapshot(
                    DomainAgentAsyncTaskMetadata.clearRunMetadata(latestRun.metadata())));
        } else {
            persistExternalPartialAssistant(command, latestRun, skillId);
        }
        ChatEvent stored = chatStreamService.appendWithoutPublish(command.event());
        ChatRun committedRun = runRepository.finalizeExternalTerminal(
                new ChatRunRepository.ExternalTerminalFinalize(
                        latestRun.id(),
                        latestRun.tenantId(),
                        latestRun.userId(),
                        latestRun.sessionId(),
                        terminalStatus,
                        stored.sequence(),
                        latestRun.cancelReason(),
                        finishedAt
                ));
        // run.cancelled 表示用户 stop 已经取得控制权；watchdog 只负责闭合终态，不能恢复等待。
        if (terminalStatus == ChatRunStatus.CANCELLED) {
            cancelContinuationInteractionClaim(committedRun, command.interactionId());
        } else {
            releaseContinuationInteractionClaim(committedRun, command.interactionId());
        }
        markExecutionTerminal(stored);
        return new ExternalTerminalCommitResult(stored, committedRun, true);
    }

    private void persistAsyncTerminalAssistant(ChatRun run, ChatRunStatus terminalStatus) {
        String assistantMessageId = DomainAgentAsyncTaskMetadata.assistantMessageId(run);
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            assistantMessageId = run.assistantMessageId();
        }
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return;
        }
        ChatSession session = sessionService.requireSessionForInternalUpdate(
                run.tenantId(), run.userId(), run.sessionId());
        ChatMessage existing = sessionService.requireAssistantForInternalUpdate(session, assistantMessageId);
        String status = terminalStatus == ChatRunStatus.CANCELLED ? "CANCELLED" : "FAILED";
        String metadata = DomainAgentAsyncTaskMetadata.mergeAssistantMetadata(
                objectMapper, existing.metadataJson(), status, null);
        sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                run.tenantId(), run.userId(), session, existing.id(), existing.content(), run.id(),
                java.util.List.of(), metadata, false));
    }

    private void persistExternalPartialAssistant(
            ExternalTerminalCommitCommand command,
            ChatRun latestRun,
            String skillId) {
        AssistantMessageSaveCommand partialAssistant = command.partialAssistant();
        if (partialAssistant == null) {
            return;
        }
        String expectedId = partialAssistant.normalizedMessageId();
        ChatMessage saved;
        if (interactionContinuation(latestRun) && !InteractionMessageStrategy.newTurn(latestRun)) {
            String assistantMessageId = interactionAssistantMessageId(latestRun);
            if (assistantMessageId == null || !assistantMessageId.equals(expectedId)) {
                throw new IllegalStateException("Interaction stop partial assistant 必须复用原 assistantMessageId");
            }
            saved = sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                    partialAssistant.tenantId(),
                    partialAssistant.userId(),
                    partialAssistant.session(),
                    assistantMessageId,
                    partialAssistant.content(),
                    partialAssistant.runId(),
                    partialAssistant.safePartDrafts(),
                    assistantMetadata(partialAssistant.metadataJson(), skillId)
            ));
        } else {
            saved = sessionService.saveAssistantMessage(withMetadata(
                    partialAssistant,
                    assistantMetadata(partialAssistant.metadataJson(), skillId)));
        }
        if (expectedId == null || !expectedId.equals(saved.id())) {
            throw new IllegalStateException("stop partial assistant ID 与预分配 ID 不一致");
        }
        bindAssistantMessage(latestRun.id(), saved.id());
    }

    private boolean interactionContinuation(ChatRun run) {
        return metadataText(run, INTERACTION_ID_METADATA) != null;
    }

    private String interactionAssistantMessageId(ChatRun run) {
        return metadataText(run, INTERACTION_ASSISTANT_MESSAGE_ID_METADATA);
    }

    private String metadataText(ChatRun run, String key) {
        if (run == null || run.metadata() == null || key == null) {
            return null;
        }
        Object value = run.metadata().get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? null : text;
    }

    /**
     * 幂等修复终态 continuation run 遗留的 Interaction claim。
     */
    @Transactional(timeoutString = "${financeex.chat-run.external-terminal-transaction-timeout-seconds:10}")
    public int reconcileTerminalInteraction(ChatRun run) {
        if (run == null || (run.status() != ChatRunStatus.CANCELLED && run.status() != ChatRunStatus.FAILED)) {
            return 0;
        }
        return run.status() == ChatRunStatus.CANCELLED
                ? cancelContinuationInteractionClaim(run, null)
                : releaseContinuationInteractionClaim(run);
    }

    private ChatEvent append(ChatEvent event, TerminalCommitContext context) {
        return chatStreamService.appendWithExecutionGuard(event, context.executionClaim());
    }

    private void lockOwnerTerminalSession(TerminalCommitContext context) {
        validateOwnerTerminalContext(context);
        sessionService.lockForMessageMutation(
                context.user().tenantId(), context.user().ownerUserId(), context.session());
    }

    private void lockExternalPartialAssistantSession(ExternalTerminalCommitCommand command) {
        AssistantMessageSaveCommand partialAssistant = command.partialAssistant();
        if (partialAssistant == null) {
            ChatRun run = command.run();
            if (DomainAgentAsyncTaskMetadata.isAsyncRunning(run)) {
                ChatSession session = sessionService.requireSessionForInternalUpdate(
                        run.tenantId(), run.userId(), run.sessionId());
                sessionService.lockForMessageMutation(run.tenantId(), run.userId(), session);
            }
            return;
        }
        ChatRun run = command.run();
        ChatSession session = partialAssistant.session();
        if (session == null
                || !run.tenantId().equals(partialAssistant.tenantId())
                || !run.userId().equals(partialAssistant.userId())
                || !run.sessionId().equals(session.id())) {
            throw new IllegalArgumentException("stop partial assistant 与 run 归属不一致");
        }
        sessionService.lockForMessageMutation(
                partialAssistant.tenantId(), partialAssistant.userId(), session);
    }

    private void fenceOwnerTerminalCommit(TerminalCommitContext context) {
        validateOwnerTerminalContext(context);
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

    private void validateOwnerTerminalContext(TerminalCommitContext context) {
        if (context == null || context.user() == null || context.session() == null
                || context.runId() == null || context.runId().isBlank() || context.executionClaim() == null) {
            throw new IllegalArgumentException("owner 终态提交上下文不完整");
        }
    }

    private ChatMessage saveCompletedAssistant(
            CompletedCommitCommand command,
            String skillId) {
        TerminalCommitContext context = command.context();
        UserContext user = context.user();
        if (context.continuationInteractionRequest() == null || newTurnInteraction(context)) {
            return sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    context.session(),
                    context.assistant().finalContent(),
                    context.runId(),
                    context.messagePlan().userMessage().id(),
                    context.messagePlan().regeneratedFromMessageId(),
                    context.assistant().parts(),
                    assistantMetadata(context.assistant().assistantMetadata(null), skillId),
                    command.target().assistantMessageId(),
                    context.assistant().appendAnswerPart()
            ));
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                user.tenantId(),
                user.ownerUserId(),
                context.session(),
                context.continuationInteractionRequest().assistantMessageId(),
                context.assistant().finalContent(),
                context.runId(),
                context.assistant().parts(),
                assistantMetadata(context.assistant().assistantMetadata(null), skillId),
                context.assistant().appendAnswerPart()
        ));
    }

    private ChatMessage saveWaitingAssistant(
            WaitingUserCommitCommand command,
            String skillId) {
        TerminalCommitContext context = command.context();
        UserContext user = context.user();
        ChatInteractionRequest continuation = context.continuationInteractionRequest();
        if (continuation == null || newTurnInteraction(context)) {
            if (continuation != null) {
                validateNewTurnWaitingRequest(context, command);
            }
            return sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    context.session(),
                    context.assistant().finalContent(),
                    context.runId(),
                    context.messagePlan().userMessage().id(),
                    context.messagePlan().regeneratedFromMessageId(),
                    context.assistant().parts(),
                    assistantMetadata(
                            context.assistant().assistantMetadata(WAITING_ASSISTANT_METADATA), skillId),
                    command.target().assistantMessageId(),
                    context.assistant().appendAnswerPart()
                            && appendWaitingAnswer(command.waitingRequest())
            ));
        }
        String existingAssistantId = continuation.assistantMessageId();
        String nextAssistantId = command.waitingRequest() == null
                ? null
                : command.waitingRequest().assistantMessageId();
        if (existingAssistantId == null || existingAssistantId.isBlank()
                || !existingAssistantId.equals(command.target().assistantMessageId())
                || !existingAssistantId.equals(nextAssistantId)) {
            throw new IllegalStateException("多轮 Interaction 必须复用同一 assistantMessageId");
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                user.tenantId(),
                user.ownerUserId(),
                context.session(),
                existingAssistantId,
                context.assistant().finalContent(),
                context.runId(),
                context.assistant().parts(),
                assistantMetadata(context.assistant().assistantMetadata(WAITING_ASSISTANT_METADATA), skillId),
                context.assistant().appendAnswerPart()
                        && appendWaitingAnswer(command.waitingRequest())
        ));
    }

    private boolean appendWaitingAnswer(ChatInteractionRequest waitingRequest) {
        return waitingRequest == null
                || waitingRequest.interactionType() != ChatInteractionType.ROUTE_SWITCH_CONFIRMATION;
    }

    private String assistantMetadata(String metadataJson, String skillId) {
        MessageSkillMetadata.MergeResult result = messageSkillMetadata.replace(metadataJson, skillId);
        if (result.invalidExistingMetadata()) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DESERIALIZATION_FAILED,
                            "Assistant metadata is invalid; skillId projection was skipped")
                    .operation("chat-message.skill-metadata")
                    .build());
        }
        return result.metadataJson();
    }

    private AssistantMessageSaveCommand withMetadata(
            AssistantMessageSaveCommand command,
            String metadataJson) {
        return new AssistantMessageSaveCommand(
                command.tenantId(),
                command.userId(),
                command.session(),
                command.content(),
                command.runId(),
                command.parentMessageId(),
                command.regeneratedFromMessageId(),
                command.safePartDrafts(),
                metadataJson,
                command.messageId(),
                command.appendAnswerPart());
    }

    private void validateNewTurnWaitingRequest(TerminalCommitContext context, WaitingUserCommitCommand command) {
        ChatInteractionRequest waiting = command.waitingRequest();
        String expectedUserId = context.messagePlan() == null || context.messagePlan().userMessage() == null
                ? null
                : context.messagePlan().userMessage().id();
        String expectedAssistantId = command.target() == null ? null : command.target().assistantMessageId();
        if (waiting == null || expectedUserId == null || expectedAssistantId == null
                || !expectedUserId.equals(waiting.userMessageId())
                || !expectedAssistantId.equals(waiting.assistantMessageId())) {
            throw new IllegalStateException("意图澄清下一轮 Interaction 必须关联本轮新 user/assistant 消息");
        }
    }

    private boolean newTurnInteraction(TerminalCommitContext context) {
        return context != null && InteractionMessageStrategy.newTurn(context.continuationInteractionRequest());
    }

    private boolean reusableInteraction(TerminalCommitContext context) {
        return context != null && context.continuationInteractionRequest() != null
                && !newTurnInteraction(context);
    }

    private boolean cancelFailedRelayInteraction(TerminalCommitContext context, ChatEvent event) {
        if (!reusableInteraction(context) || event == null || !"run.failed".equals(event.type())) {
            return false;
        }
        RuntimeInteractionDispatchState dispatchState = context.interactionDispatchState();
        if (dispatchState == null || !dispatchState.trackedInteraction()) {
            return false;
        }
        Map<String, Object> payload = event.payload() == null ? Map.of() : event.payload();
        return dispatchState.cancelInteractionAfterFailure()
                || RUNTIME_SESSION_UNAVAILABLE.equals(String.valueOf(payload.get("code")));
    }

    /**
     * 回答可能已送达 Relay 时，只取消仍由当前 continuation run 持有的 ACTIVE Binding。
     */
    private RuntimeBinding cancelFailedRelayInteractionBinding(
            RuntimeBinding binding,
            TerminalCommitContext context) {
        ChatInteractionRequest interaction = context.continuationInteractionRequest();
        RuntimeBinding candidate = binding;
        if (candidate == null && interaction != null
                && interaction.runtimeBindingId() != null && !interaction.runtimeBindingId().isBlank()) {
            candidate = runtimeBindingRepository.findById(interaction.runtimeBindingId()).orElse(null);
        }
        if (candidate == null || interaction == null) {
            return candidate;
        }
        boolean cancelled = context.runId().equals(candidate.lastRunId())
                ? runtimeBindingRepository.cancelActiveForRun(candidate.id(), context.runId())
                : runtimeBindingRepository.cancelActiveForInteraction(
                        candidate, interaction.sourceRunId(), context.runId());
        if (cancelled) {
            return candidate.withStatus(RuntimeBindingStatus.CANCELLED);
        }
        RuntimeBinding fallback = candidate;
        return runtimeBindingRepository.findById(candidate.id())
                .orElse(fallback.withStatus(RuntimeBindingStatus.CANCELLED));
    }

    private void bindAssistantMessage(String runId, String assistantMessageId) {
        runRepository.findById(runId)
                .ifPresent(run -> runRepository.save(run.withAssistantMessageId(assistantMessageId)));
    }

    private void advanceLatestMessageSeq(TerminalCommitContext context, ChatEvent stored) {
        sessionService.advanceLatestMessageSeq(context.user(), context.session(), stored.sequence());
    }

    private ChatRun observeRun(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return null;
        }
        if ("message.delta".equals(event.type()) || "message.snapshot".equals(event.type())
                || "message.completed".equals(event.type())) {
            return null;
        }
        return runRepository.findById(event.runId())
                .map(run -> saveObservedRun(run, event))
                .orElse(null);
    }

    private int releaseContinuationInteractionClaim(ChatRun run) {
        return releaseContinuationInteractionClaim(run, null);
    }

    private int releaseContinuationInteractionClaim(ChatRun run, String explicitInteractionId) {
        if (chatInteractionService == null || run == null || InteractionMessageStrategy.newTurn(run)) {
            return 0;
        }
        Object value = run.metadata() == null ? null : run.metadata().get("interactionId");
        String interactionId = explicitInteractionId == null || explicitInteractionId.isBlank()
                ? (value == null ? null : String.valueOf(value).trim())
                : explicitInteractionId.trim();
        if (interactionId == null || interactionId.isBlank()) {
            return 0;
        }
        return chatInteractionService.markWaitingForRun(
                run.tenantId(), run.userId(), interactionId, run.id());
    }

    private int cancelContinuationInteractionClaim(ChatRun run, String explicitInteractionId) {
        if (chatInteractionService == null || run == null || InteractionMessageStrategy.newTurn(run)) {
            return 0;
        }
        String interactionId = continuationInteractionId(run, explicitInteractionId);
        if (interactionId == null) {
            return 0;
        }
        return chatInteractionService.cancelRespondingForRun(
                run.tenantId(), run.userId(), interactionId, run.id(), java.time.Instant.now());
    }

    private String continuationInteractionId(ChatRun run, String explicitInteractionId) {
        Object value = run.metadata() == null ? null : run.metadata().get(INTERACTION_ID_METADATA);
        String interactionId = explicitInteractionId == null || explicitInteractionId.isBlank()
                ? (value == null ? null : String.valueOf(value).trim())
                : explicitInteractionId.trim();
        return interactionId == null || interactionId.isBlank() ? null : interactionId;
    }

    private ChatRun saveObservedRun(ChatRun run, ChatEvent event) {
        if (run.status().terminal() || (run.status() == ChatRunStatus.CANCELLING
                && !"run.cancelled".equals(event.type()) && !"run.failed".equals(event.type()))) {
            return run;
        }
        ChatRun next = switch (event.type()) {
            case "run.started" -> run.withFirstSeq(event.sequence());
            case "run.completed" -> run.completed(event.sequence());
            case "run.waiting_user" -> run.waitingUser(event.sequence());
            case "run.failed" -> run.failed(event.sequence());
            case "run.cancelled" -> run.cancelled(event.sequence());
            default -> run.withLastSeq(event.sequence());
        };
        Object runtimeSessionId = event.payload() == null ? null : event.payload().get("runtimeSessionId");
        if (runtimeSessionId != null && !String.valueOf(runtimeSessionId).isBlank()
                && !String.valueOf(runtimeSessionId).equals(next.runtimeSessionId())) {
            next = next.withRuntimeSessionId(String.valueOf(runtimeSessionId));
        }
        return runRepository.save(next);
    }

    private void markExecutionTerminal(ChatEvent event) {
        ChatRunExecutionStatus terminalStatus = switch (event.type()) {
            case "run.completed" -> ChatRunExecutionStatus.COMPLETED;
            case "run.waiting_user" -> ChatRunExecutionStatus.WAITING_USER;
            case "run.failed" -> ChatRunExecutionStatus.FAILED;
            case "run.cancelled" -> ChatRunExecutionStatus.CANCELLED;
            default -> null;
        };
        if (terminalStatus != null) {
            runLeaseService.markTerminal(event.runId(), terminalStatus);
        }
    }

    private RuntimeBinding refreshBinding(TerminalCommitContext context, String leafMessageId) {
        RuntimeBinding binding = context.bindingRef().get();
        if (binding == null) {
            return null;
        }
        if (binding.status() != RuntimeBindingStatus.ACTIVE) {
            return binding;
        }
        boolean establishedRelay = RELAY_PROVIDER.equals(binding.provider());
        RuntimeBinding next = binding.withRun(context.runId(), expiresAt(binding.provider(), establishedRelay));
        if (establishedRelay) {
            next = markRelaySessionEstablished(next, next.runtimeSessionId());
        }
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return runtimeBindingRepository.save(next);
    }

    private RuntimeBinding completeBinding(TerminalCommitContext context, String leafMessageId) {
        RuntimeBinding binding = context.bindingRef().get();
        if (binding == null) {
            return null;
        }
        if (!DOMAIN_AGENT_PROVIDER.equals(binding.provider())) {
            RuntimeBinding next = markRelaySessionEstablished(binding, binding.runtimeSessionId())
                    .withRun(context.runId(), null);
            if (leafMessageId != null && !leafMessageId.isBlank()
                    && !leafMessageId.equals(next.leafMessageId())) {
                next = next.withLeafMessageId(leafMessageId);
            }
            return runtimeBindingRepository.save(next.withStatus(RuntimeBindingStatus.RESUMABLE));
        }
        return refreshBinding(context, leafMessageId);
    }

    private RuntimeBinding observeRuntimeBindingEvent(RuntimeBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null) {
            return binding;
        }
        Object runtimeSessionId = event.payload().get("runtimeSessionId");
        if (runtimeSessionId == null || String.valueOf(runtimeSessionId).isBlank()) {
            return binding;
        }
        String nextRuntimeSessionId = String.valueOf(runtimeSessionId);
        boolean sessionIdChanged = !nextRuntimeSessionId.equals(binding.runtimeSessionId());
        boolean establishRelay = RELAY_PROVIDER.equals(binding.provider())
                && (!relaySessionEstablished(binding) || binding.expiresAt() != null);
        if (!sessionIdChanged && !establishRelay) {
            return binding;
        }
        RuntimeBinding next = sessionIdChanged
                ? binding.withRuntimeSessionId(nextRuntimeSessionId)
                : binding;
        next = markRelaySessionEstablished(next, nextRuntimeSessionId);
        return runtimeBindingRepository.save(next);
    }

    private RuntimeBinding invalidateUnavailableRuntimeSession(RuntimeBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null
                || !"run.failed".equals(event.type())
                || !RUNTIME_SESSION_UNAVAILABLE.equals(String.valueOf(event.payload().get("code")))) {
            return binding;
        }
        RuntimeBinding cancelled = binding.withStatus(RuntimeBindingStatus.CANCELLED);
        return runtimeBindingRepository.save(cancelled);
    }

    private RuntimeBinding markRelaySessionEstablished(RuntimeBinding binding, String runtimeSessionId) {
        if (binding == null || !RELAY_PROVIDER.equals(binding.provider())) {
            return binding;
        }
        RuntimeBinding next = binding;
        if (runtimeSessionId != null && !runtimeSessionId.isBlank()
                && !runtimeSessionId.equals(next.runtimeSessionId())) {
            next = next.withRuntimeSessionId(runtimeSessionId);
        }
        Map<String, Object> metadata = new LinkedHashMap<>(next.metadata());
        metadata.put(RUNTIME_SESSION_ESTABLISHED, true);
        return next.withMetadata(metadata).withExpiresAt(null);
    }

    private boolean relaySessionEstablished(RuntimeBinding binding) {
        return binding != null
                && RELAY_PROVIDER.equals(binding.provider())
                && (binding.status() == RuntimeBindingStatus.RESUMABLE
                || Boolean.TRUE.equals(binding.metadata().get(RUNTIME_SESSION_ESTABLISHED)));
    }

    private java.time.Instant expiresAt(String provider, boolean relaySessionEstablished) {
        return RuntimeBindingExpirationPolicy.expiresAt(runtimeBindingTtl,
                RELAY_PROVIDER.equals(provider) && relaySessionEstablished);
    }

    public record TerminalCommitContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            ChatInteractionRequest continuationInteractionRequest,
            RuntimeInteractionDispatchState interactionDispatchState
    ) {
        public TerminalCommitContext(
                UserContext user,
                ChatSession session,
                ChatRunMessagePlan messagePlan,
                AtomicReference<RuntimeBinding> bindingRef,
                AssistantAssembly assistant,
                String runId,
                RunExecutionClaim executionClaim,
                ChatInteractionRequest continuationInteractionRequest) {
            this(user, session, messagePlan, bindingRef, assistant, runId, executionClaim,
                    continuationInteractionRequest, RuntimeInteractionDispatchState.untracked());
        }
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

        public static ExternalTerminalCommitCommand asyncTimeout(ChatEvent event, ChatRun run) {
            return new ExternalTerminalCommitCommand(
                    event,
                    run,
                    ChatRunRepository.ExternalTerminalGuard.ASYNC_TIMEOUT,
                    null,
                    null,
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
