package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.repository.ChatRunRepository;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunExecutionStatus;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;

final class ChatRunTerminalStateObserver {
    private final ChatRunRepository runRepository;
    private final ChatRunLeaseApplicationService runLeaseService;
    private final RuntimeBindingService runtimeBindingService;

    ChatRunTerminalStateObserver(ChatRunRepository runRepository,
                                 ChatRunLeaseApplicationService runLeaseService,
                                 RuntimeBindingService runtimeBindingService) {
        this.runRepository = runRepository;
        this.runLeaseService = runLeaseService;
        this.runtimeBindingService = runtimeBindingService;
    }

    ChatRun observeRun(ChatEvent event) {
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

    void markExecutionTerminal(ChatEvent event) {
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

    RuntimeBinding refreshBinding(ChatRunTerminalCommitService.TerminalCommitContext context,
                                  String leafMessageId) {
        RuntimeBinding binding = context.bindingRef().get();
        if (binding == null) {
            return null;
        }
        return runtimeBindingService.refreshInCurrentTransaction(binding, context.runId(), leafMessageId);
    }

    RuntimeBinding completeBinding(ChatRunTerminalCommitService.TerminalCommitContext context,
                                   String leafMessageId) {
        RuntimeBinding binding = context.bindingRef().get();
        if (binding == null) {
            return null;
        }
        return runtimeBindingService.completeInCurrentTransaction(binding, context.runId(), leafMessageId);
    }

    RuntimeBinding observeRuntimeBindingEvent(RuntimeBinding binding, ChatEvent event) {
        return runtimeBindingService.observeEventInCurrentTransaction(binding, event);
    }

    RuntimeBinding invalidateUnavailableRuntimeSession(RuntimeBinding binding, ChatEvent event) {
        return runtimeBindingService.invalidateUnavailableInCurrentTransaction(binding, event);
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

}
