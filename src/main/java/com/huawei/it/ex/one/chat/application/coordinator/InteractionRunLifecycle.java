package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.InteractionMessageStrategy;
import com.huawei.it.ex.one.chat.application.model.RunEventPipelineContext;
import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunLeaseApplicationService;
import com.huawei.it.ex.one.chat.application.service.CreateChatRunContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import java.util.Map;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Shared lifecycle operations for the existing Interaction continuation run variants. */
@Component
public class InteractionRunLifecycle {
    private static final String INTERACTION_ASSISTANT_MESSAGE_ID_METADATA = "interactionAssistantMessageId";
    private final ChatRunApplicationService chatRunService;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatRunFailureCoordinator failureCoordinator;

    public InteractionRunLifecycle(ChatRunApplicationService chatRunService,
                                   ChatRunLeaseApplicationService chatRunLeaseService,
                                   ChatRunStartCoordinator runStartCoordinator,
                                   ChatRunFailureCoordinator failureCoordinator) {
        this.chatRunService = chatRunService;
        this.chatRunLeaseService = chatRunLeaseService;
        this.runStartCoordinator = runStartCoordinator;
        this.failureCoordinator = failureCoordinator;
    }

    public Map<String, Object> metadata(ChatInteractionRequest interaction) {
        String assistantMessageId = interaction == null ? null : firstText(interaction.assistantMessageId());
        if (interaction == null || assistantMessageId == null || assistantMessageId.isBlank()) {
            throw new IllegalStateException("Interaction continuation 缺少 assistantMessageId");
        }
        return Map.of(
                "interactionId", interaction.id(),
                "interactionType", interaction.interactionType().name(),
                InteractionMessageStrategy.METADATA_KEY,
                InteractionMessageStrategy.forInteraction(interaction).name(),
                INTERACTION_ASSISTANT_MESSAGE_ID_METADATA, assistantMessageId);
    }

    public ChatRun create(CreateChatRunContext context, ChatInteractionRequest interaction) {
        return chatRunService.createInteractionRunning(context, interaction.id());
    }

    public void trackRun(RunStartAttempt startAttempt, ChatRun run, String stage) {
        runStartCoordinator.trackRun(startAttempt, run, stage);
    }

    public RunExecutionClaim startExecution(ChatRun run,
                                            ChatInteractionRequest interaction) {
        return chatRunLeaseService.startInteractionRun(run, interaction.id());
    }

    public void trackExecution(RunStartAttempt startAttempt,
                               RunExecutionClaim executionClaim,
                               String stage) {
        runStartCoordinator.trackExecution(startAttempt, executionClaim, stage);
    }

    public void synchronizeCommittedRunCache(ChatRun run) {
        chatRunService.synchronizeCommittedRunCache(run);
    }

    public Flux<ChatEvent> failInitialization(ChatRun run,
                                              ChatInteractionRequest interaction,
                                              RuntimeException failure) {
        return failureCoordinator.failExecutionInitialization(run, interaction, failure);
    }

    public Flux<ChatEvent> failContinuation(RunEventPipelineContext context,
                                            RuntimeException failure) {
        return failureCoordinator.failInteractionContinuation(context, failure);
    }

    private String firstText(Object value) {
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }
}
