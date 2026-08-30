/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.MessageSkillContext;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.runtime.RelayOutputModeMetadata;

import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.Objects;

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

    /**
     * 从可信 source run 继承留存策略，避免 continuation 重新按 FULL 保存真实业务输出。
     */
    AgentDataPersistenceState inheritedPersistenceState(
            UserContext user,
            ChatInteractionRequest interaction) {
        return inheritedRunState(user, interaction).persistenceState();
    }

    InheritedRunState inheritedRunState(
            UserContext user,
            ChatInteractionRequest interaction) {
        if (interaction == null || interaction.sourceRunId() == null
                || interaction.sourceRunId().isBlank()) {
            throw new IllegalStateException("Interaction continuation 缺少 sourceRunId");
        }
        ChatRun sourceRun = chatRunService.requireOwnedRun(user, interaction.sourceRunId());
        if (interaction.sessionId() == null || interaction.sessionId().isBlank()
                || !Objects.equals(interaction.sessionId(), sourceRun.sessionId())) {
            throw new IllegalStateException("Interaction continuation 的 source run 会话不匹配");
        }
        return new InheritedRunState(
                AgentDataPersistenceState.inheritFromRunMetadata(sourceRun.metadata(), null),
                RelayOutputModeMetadata.fromRunMetadata(sourceRun.metadata()),
                lastInvocationSkillId(sourceRun));
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

    private String lastInvocationSkillId(ChatRun sourceRun) {
        String skillId = MessageSkillContext.runSkillId(sourceRun.metadata());
        if (skillId != null) {
            return skillId;
        }
        if (!"domain-agent".equals(sourceRun.runtimeProvider())
                || sourceRun.agentCode() == null || sourceRun.agentCode().isBlank()) {
            return null;
        }
        return sourceRun.agentCode().trim();
    }

    record InheritedRunState(
            AgentDataPersistenceState persistenceState,
            RelayOutputMode relayOutputMode,
            String invocationSkillId
    ) {
        InheritedRunState(
                AgentDataPersistenceState persistenceState,
                RelayOutputMode relayOutputMode) {
            this(persistenceState, relayOutputMode, null);
        }

        InheritedRunState {
            persistenceState = persistenceState == null
                    ? AgentDataPersistenceState.full()
                    : persistenceState;
            relayOutputMode = relayOutputMode == null
                    ? RelayOutputMode.FULL_STREAM
                    : relayOutputMode;
        }
    }
}
