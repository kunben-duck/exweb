package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ErrorEvent;

import reactor.core.publisher.Flux;

/** Preserves execution-initialization and post-creation failure closure behavior. */
final class ChatRunFailureCoordinator {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunFailureCoordinator.class);
    private final ChatRunTerminalCommitService terminalCommitService;
    private final ChatRunApplicationService chatRunService;
    private final ChatStreamApplicationService chatStreamService;
    private final ChatEventPersistenceCoordinator eventPersistenceCoordinator;
    private final LocalChatRunExecutionRegistry runExecutionRegistry;
    private final ChatRunFailureMapper runFailureMapper = new ChatRunFailureMapper();

    ChatRunFailureCoordinator(ChatRunTerminalCommitService terminalCommitService,
                              ChatRunApplicationService chatRunService,
                              ChatStreamApplicationService chatStreamService,
                              ChatEventPersistenceCoordinator eventPersistenceCoordinator,
                              LocalChatRunExecutionRegistry runExecutionRegistry) {
        this.terminalCommitService = terminalCommitService;
        this.chatRunService = chatRunService;
        this.chatStreamService = chatStreamService;
        this.eventPersistenceCoordinator = eventPersistenceCoordinator;
        this.runExecutionRegistry = runExecutionRegistry;
    }

    Flux<ChatEvent> failInteractionContinuation(RunEventPipelineContext context,
                                                RuntimeException failure) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                        "Interaction continuation failed after run creation; falling back to run.failed")
                .runId(context.runId())
                .sessionId(context.session().id())
                .operation("interaction.continue")
                .attribute("interactionId", context.continuationInteractionRequest() == null
                        ? null : context.continuationInteractionRequest().id())
                .build(), failure);
        return eventPersistenceCoordinator.persistAndPublish(
                        Flux.just(runtimeErrorEvent(
                                context.runId(), context.session().id(), failure)),
                        context)
                .doFinally(ignored -> runExecutionRegistry.complete(context.executionClaim()));
    }

    Flux<ChatEvent> failExecutionInitialization(ChatRun run,
                                                ChatInteractionRequest interaction,
                                                RuntimeException failure) {
        String message = blankToDefault(
                failure == null ? null : failure.getMessage(),
                "run execution 初始化失败");
        ChatEvent failed = ErrorEvent.of(
                run.id(), run.sessionId(), "RUN_EXECUTION_INIT_FAILED", message);
        if (terminalCommitService == null) {
            return Flux.error(new IllegalStateException(
                    "ChatRunTerminalCommitService 未配置，无法安全提交 execution 初始化失败终态",
                    failure));
        }
        ChatRunTerminalCommitService.ExternalTerminalCommitResult result =
                terminalCommitService.commitExternalTerminal(
                        ChatRunTerminalCommitService.ExternalTerminalCommitCommand.executionInitFailure(
                                failed,
                                run,
                                interaction == null ? null : interaction.id()));
        chatRunService.synchronizeCommittedRunCache(result.run());
        if (!result.committed()) {
            log.info("Run execution initialization terminal claim was not acquired. runId={}, currentStatus={}",
                    run.id(), result.run() == null ? null : result.run().status());
            return Flux.empty();
        }
        try {
            chatStreamService.publishPersisted(result.event());
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                            "Run execution initialization failure committed but realtime publish failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("chat-run.execution-init-failure-publish")
                    .build());
        }
        return Flux.just(result.event());
    }

    ErrorEvent runtimeErrorEvent(String runId, String sessionId, Throwable failure) {
        return runFailureMapper.toEvent(runId, sessionId, failure);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
