package com.huawei.finance.front.one.application.service.chat;

import com.huawei.finance.front.one.application.integration.conversation.ChatRunRepository;
import com.huawei.finance.front.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatHitlRequest;
import com.huawei.finance.front.one.domain.chat.ChatMessage;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunMessagePlan;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatSession;
import com.huawei.finance.front.one.domain.chat.RunExecutionClaim;
import com.huawei.finance.front.one.domain.runtime.RuntimeBinding;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Chat run 终态数据库提交器。
 *
 * <p>本服务只做本地事实源写入，不发布 Redis/WebSocket，也不订阅 Reactor 流。这样
 * {@code run.waiting_user} 这类前端依赖多张表的终态可以在短事务内一次提交成功，避免出现
 * event 已可恢复但 HITL 请求或 assistant part 缺失的半截状态。</p>
 */
@Service
public class ChatRunTerminalCommitService {
    private static final String WAITING_ASSISTANT_METADATA = "{\"finishReason\":\"WAITING_USER\"}";

    private final ChatStreamApplicationService chatStreamService;
    private final SessionApplicationService sessionService;
    private final ChatRunRepository runRepository;
    private final ChatRunLeaseApplicationService runLeaseService;
    private final RuntimeBindingRepository runtimeBindingRepository;
    private final ChatHitlApplicationService chatHitlService;
    private final Duration runtimeBindingTtl;

    public ChatRunTerminalCommitService(ChatStreamApplicationService chatStreamService,
                                        SessionApplicationService sessionService,
                                        ChatRunRepository runRepository,
                                        ChatRunLeaseApplicationService runLeaseService,
                                        RuntimeBindingRepository runtimeBindingRepository,
                                        ChatHitlApplicationService chatHitlService,
                                        @Value("${financeex.runtime-binding.ttl:3d}") Duration runtimeBindingTtl) {
        this.chatStreamService = chatStreamService;
        this.sessionService = sessionService;
        this.runRepository = runRepository;
        this.runLeaseService = runLeaseService;
        this.runtimeBindingRepository = runtimeBindingRepository;
        this.chatHitlService = chatHitlService;
        this.runtimeBindingTtl = runtimeBindingTtl == null ? Duration.ofDays(3) : runtimeBindingTtl;
    }

    @Transactional
    public CommitResult commitCompleted(CompletedCommitCommand command) {
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        ChatMessage savedAssistant = saveCompletedAssistant(command);
        bindAssistantMessage(stored.runId(), savedAssistant.id());
        RuntimeBinding binding = refreshBinding(command.context(), savedAssistant.id());
        if (command.context().continuationHitlRequest() != null) {
            chatHitlService.markAnswered(command.context().continuationHitlRequest());
        }
        observeRun(stored);
        markExecutionTerminal(stored);
        binding = observeRuntimeBindingEvent(binding, stored);
        return new CommitResult(stored, binding);
    }

    @Transactional
    public CommitResult commitWaitingUser(WaitingUserCommitCommand command) {
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        ChatMessage savedAssistant = sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                command.context().user().tenantId(),
                command.context().user().ownerUserId(),
                command.context().session(),
                command.context().assistant().finalContent(),
                command.context().runId(),
                command.context().messagePlan().userMessage().id(),
                command.context().messagePlan().regeneratedFromMessageId(),
                command.context().assistant().parts(),
                WAITING_ASSISTANT_METADATA,
                command.target().assistantMessageId()
        ));
        bindAssistantMessage(stored.runId(), savedAssistant.id());
        RuntimeBinding binding = refreshBinding(command.context(), savedAssistant.id());
        if (command.context().continuationHitlRequest() != null) {
            chatHitlService.markAnswered(command.context().continuationHitlRequest());
        }
        chatHitlService.saveWaiting(command.waitingRequest());
        observeRun(stored);
        markExecutionTerminal(stored);
        binding = observeRuntimeBindingEvent(binding, stored);
        return new CommitResult(stored, binding);
    }

    @Transactional
    public CommitResult commitTerminalOnly(TerminalOnlyCommitCommand command) {
        ChatEvent stored = append(command.event(), command.context());
        command.context().assistant().observe(stored);
        observeRun(stored);
        if (command.context().continuationHitlRequest() != null
                && ("run.failed".equals(stored.type()) || "run.cancelled".equals(stored.type()))) {
            chatHitlService.markWaiting(command.context().continuationHitlRequest());
        }
        markExecutionTerminal(stored);
        RuntimeBinding binding = observeRuntimeBindingEvent(command.context().bindingRef().get(), stored);
        return new CommitResult(stored, binding);
    }

    private ChatEvent append(ChatEvent event, TerminalCommitContext context) {
        return chatStreamService.appendWithExecutionGuard(event, context.executionClaim());
    }

    private ChatMessage saveCompletedAssistant(CompletedCommitCommand command) {
        TerminalCommitContext context = command.context();
        UserContext user = context.user();
        if (context.continuationHitlRequest() == null) {
            return sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                    user.tenantId(),
                    user.ownerUserId(),
                    context.session(),
                    context.assistant().finalContent(),
                    context.runId(),
                    context.messagePlan().userMessage().id(),
                    context.messagePlan().regeneratedFromMessageId(),
                    context.assistant().parts(),
                    null,
                    command.target().assistantMessageId()
            ));
        }
        return sessionService.updateAssistantMessage(new AssistantMessageUpdateCommand(
                user.tenantId(),
                user.ownerUserId(),
                context.session(),
                context.continuationHitlRequest().assistantMessageId(),
                context.assistant().finalContent(),
                context.runId(),
                context.assistant().parts(),
                null
        ));
    }

    private void bindAssistantMessage(String runId, String assistantMessageId) {
        runRepository.findById(runId)
                .ifPresent(run -> runRepository.save(run.withAssistantMessageId(assistantMessageId)));
    }

    private void observeRun(ChatEvent event) {
        if (event == null || event.runId() == null || event.runId().isBlank()) {
            return;
        }
        if ("message.delta".equals(event.type()) || "message.snapshot".equals(event.type())
                || "message.completed".equals(event.type())) {
            return;
        }
        runRepository.findById(event.runId()).ifPresent(run -> saveObservedRun(run, event));
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
        RuntimeBinding next = binding.withRun(context.runId(), expiresAt());
        if (leafMessageId != null && !leafMessageId.isBlank()
                && !leafMessageId.equals(next.leafMessageId())) {
            next = next.withLeafMessageId(leafMessageId);
        }
        return runtimeBindingRepository.save(next);
    }

    private RuntimeBinding observeRuntimeBindingEvent(RuntimeBinding binding, ChatEvent event) {
        if (binding == null || event == null || event.payload() == null) {
            return binding;
        }
        Object runtimeSessionId = event.payload().get("runtimeSessionId");
        if (runtimeSessionId == null || String.valueOf(runtimeSessionId).isBlank()
                || String.valueOf(runtimeSessionId).equals(binding.runtimeSessionId())) {
            return binding;
        }
        return runtimeBindingRepository.save(binding.withRuntimeSessionId(String.valueOf(runtimeSessionId)));
    }

    private java.time.Instant expiresAt() {
        return java.time.Instant.now().plus(runtimeBindingTtl);
    }

    public record TerminalCommitContext(
            UserContext user,
            ChatSession session,
            ChatRunMessagePlan messagePlan,
            AtomicReference<RuntimeBinding> bindingRef,
            AssistantAssembly assistant,
            String runId,
            RunExecutionClaim executionClaim,
            ChatHitlRequest continuationHitlRequest
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
            ChatHitlRequest waitingRequest
    ) {
    }

    public record TerminalOnlyCommitCommand(
            ChatEvent event,
            TerminalCommitContext context
    ) {
    }

    public record CommitResult(ChatEvent event, RuntimeBinding binding) {
    }
}
