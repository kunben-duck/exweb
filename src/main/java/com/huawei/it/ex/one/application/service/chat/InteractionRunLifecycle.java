package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.publisher.Flux;

import java.util.Map;

/** Shared lifecycle operations for the existing Interaction continuation run variants. */
final class InteractionRunLifecycle {
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA =
            "interactionAssistantMessageId";
    private final ChatRunApplicationService chatRunService;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatRunFailureCoordinator failureCoordinator;

    InteractionRunLifecycle(ChatRunApplicationService chatRunService,
                            ChatRunLeaseApplicationService chatRunLeaseService,
                            ChatRunStartCoordinator runStartCoordinator,
                            ChatRunFailureCoordinator failureCoordinator) {
        this.chatRunService = chatRunService;
        this.chatRunLeaseService = chatRunLeaseService;
        this.runStartCoordinator = runStartCoordinator;
        this.failureCoordinator = failureCoordinator;
    }

    Map<String, Object> metadata(ChatInteractionRequest interaction) {
        String assistantMessageId = interaction == null
                ? null
                : firstText(interaction.assistantMessageId());
        if (interaction == null || assistantMessageId == null) {
            throw new IllegalStateException(
                    "Interaction continuation 缺少 assistantMessageId");
        }
        return Map.of(
                "interactionId", interaction.id(),
                "interactionType", interaction.interactionType().name(),
                InteractionMessageStrategy.METADATA_KEY,
                InteractionMessageStrategy.forInteraction(interaction).name(),
                INTERACTION_ASSISTANT_MESSAGE_ID_METADATA,
                assistantMessageId);
    }

    ChatRun create(CreateChatRunContext context, ChatInteractionRequest interaction) {
        return chatRunService.createInteractionRunning(context, interaction.id());
    }

    void trackRun(RunStartAttempt startAttempt, ChatRun run, String stage) {
        runStartCoordinator.trackRun(startAttempt, run, stage);
    }

    RunExecutionClaim startExecution(ChatRun run,
                                     ChatInteractionRequest interaction) {
        return chatRunLeaseService.startInteractionRun(run, interaction.id());
    }

    void trackExecution(RunStartAttempt startAttempt,
                        RunExecutionClaim executionClaim,
                        String stage) {
        runStartCoordinator.trackExecution(startAttempt, executionClaim, stage);
    }

    void synchronizeCommittedRunCache(ChatRun run) {
        chatRunService.synchronizeCommittedRunCache(run);
    }

    Flux<ChatEvent> failInitialization(ChatRun run,
                                       ChatInteractionRequest interaction,
                                       RuntimeException failure) {
        return failureCoordinator.failExecutionInitialization(run, interaction, failure);
    }

    Flux<ChatEvent> failContinuation(RunEventPipelineContext context,
                                     RuntimeException failure) {
        return failureCoordinator.failInteractionContinuation(context, failure);
    }

    private String firstText(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value);
    }
}
