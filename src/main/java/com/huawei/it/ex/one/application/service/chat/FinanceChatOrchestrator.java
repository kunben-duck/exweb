package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Application orchestration behind the stable Finance chat facade. */
final class FinanceChatOrchestrator {
    private final ChatRunStartCoordinator runStartCoordinator;
    private final InteractionContinuationCoordinator interactionContinuationCoordinator;
    private final InteractionRunCoordinator interactionRunCoordinator;
    private final ChatRunExecutionCoordinator runExecutionCoordinator;
    private final ChatRunStopCoordinator stopCoordinator;

    FinanceChatOrchestrator(
            ChatRunStartCoordinator runStartCoordinator,
            InteractionContinuationCoordinator interactionContinuationCoordinator,
            InteractionRunCoordinator interactionRunCoordinator,
            ChatRunExecutionCoordinator runExecutionCoordinator,
            ChatRunStopCoordinator stopCoordinator) {
        this.runStartCoordinator = runStartCoordinator;
        this.interactionContinuationCoordinator = interactionContinuationCoordinator;
        this.interactionRunCoordinator = interactionRunCoordinator;
        this.runExecutionCoordinator = runExecutionCoordinator;
        this.stopCoordinator = stopCoordinator;
    }

    Mono<ChatRunStartResult> startRun(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        TraceContext traceSnapshot = normalizeTraceContext(traceContext);
        if (command != null
                && command.runMode() == ChatRunMode.CONTINUE_INTERACTION) {
            return Mono.defer(() -> startInteractionContinuation(
                    user,
                    traceSnapshot,
                    interactionContinuationCoordinator.responseCommand(user, command),
                    forwardHeaders));
        }
        return Mono.defer(() -> {
            validateStandardRunCommand(command);
            RuntimeForwardHeaders headers = normalizeForwardHeaders(forwardHeaders);
            return runStartCoordinator.startStandard(
                    user,
                    traceSnapshot,
                    command,
                    startAttempt -> runExecutionCoordinator.execute(
                            new ChatRunExecutionCoordinator.Request(
                                    user,
                                    traceSnapshot,
                                    command,
                                    headers,
                                    startAttempt)));
        });
    }

    Mono<ChatRunStopResult> stopRun(
            UserContext user,
            TraceContext traceContext,
            String runId,
            RuntimeForwardHeaders forwardHeaders) {
        return stopCoordinator.stopRun(
                user,
                normalizeTraceContext(traceContext),
                runId,
                "USER_STOP",
                forwardHeaders);
    }

    Flux<ChatEvent> executeRun(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        return runExecutionCoordinator.execute(
                new ChatRunExecutionCoordinator.Request(
                        user,
                        normalizeTraceContext(traceContext),
                        command,
                        normalizeForwardHeaders(forwardHeaders),
                        null));
    }

    private Mono<ChatRunStartResult> startInteractionContinuation(
            UserContext user,
            TraceContext traceContext,
            ChatInteractionResponseCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        return interactionContinuationCoordinator.start(
                user,
                traceContext,
                command,
                forwardHeaders,
                request -> interactionRunCoordinator.execute(
                        new InteractionRunCoordinator.Request(
                                request.user(),
                                request.claim(),
                                request.runId(),
                                request.forwardHeaders(),
                                request.traceContext(),
                                request.startAttempt(),
                                request.clarificationInput(),
                                request.agentMode())));
    }

    private void validateStandardRunCommand(ChatCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("创建 run 请求体不能为空");
        }
        if (command.interactionId() != null
                || command.approved() != null
                || command.scope() != null
                || !command.questionnaireAnswers().isEmpty()) {
            throw new IllegalArgumentException(
                    "Interaction 续接字段仅支持 runMode=CONTINUE_INTERACTION");
        }
    }

    private RuntimeForwardHeaders normalizeForwardHeaders(
            RuntimeForwardHeaders forwardHeaders) {
        return forwardHeaders == null
                ? RuntimeForwardHeaders.empty()
                : forwardHeaders;
    }

    private TraceContext normalizeTraceContext(TraceContext traceContext) {
        return traceContext == null ? TraceContext.empty() : traceContext;
    }
}
