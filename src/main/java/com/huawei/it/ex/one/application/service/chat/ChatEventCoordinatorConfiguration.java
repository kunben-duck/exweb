package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimePendingEventGuard;

import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares event persistence, observation and terminal workflow components. */
@Configuration(proxyBeanMethods = false)
class ChatEventCoordinatorConfiguration {
    @Bean
    ChatRunCompletionCoordinator chatRunCompletionCoordinator(
            ChatInteractionApplicationService interactionService,
            AgentRuntimeExecutor runtimeExecutor,
            IdGenerator idGenerator,
            ChatRunTerminalCommitService terminalCommitService,
            ChatStreamApplicationService streamService,
            RuntimeBindingApplicationService bindingService,
            RouteMemoryApplicationService routeMemoryService) {
        return new ChatRunCompletionCoordinator(
                interactionService,
                runtimeExecutor,
                idGenerator,
                terminalCommitService,
                streamService,
                bindingService,
                routeMemoryService);
    }

    @Bean
    ChatEventPipeline chatEventPipeline(
            ChatDeltaCoalescer chatDeltaCoalescer,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler,
            ChatRunApplicationService chatRunService,
            ChatStreamApplicationService chatStreamService,
            RuntimeBindingApplicationService runtimeBindingService,
            ChatRunCompletionCoordinator completionCoordinator,
            RuntimePendingEventGuard pendingEventGuard) {
        return new ChatEventPipeline(
                chatDeltaCoalescer,
                eventIoScheduler,
                null,
                chatRunService,
                chatStreamService,
                runtimeBindingService,
                completionCoordinator,
                pendingEventGuard);
    }

    @Bean
    ChatRunExecutionTerminalMarker chatRunExecutionTerminalMarker(
            ChatRunLeaseApplicationService chatRunLeaseService) {
        return new ChatRunExecutionTerminalMarker(chatRunLeaseService);
    }

    @Bean
    CommittedChatEventObserver committedChatEventObserver(
            ChatRunExecutionTerminalMarker terminalMarker,
            RuntimeBindingApplicationService runtimeBindingService,
            ChatStreamApplicationService chatStreamService,
            ChatRunCompletionCoordinator completionCoordinator) {
        return new CommittedChatEventObserver(
                terminalMarker,
                runtimeBindingService,
                chatStreamService,
                completionCoordinator);
    }

    @Bean
    RuntimeBindingCacheSynchronizer runtimeBindingCacheSynchronizer(
            RuntimeBindingApplicationService runtimeBindingService,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new RuntimeBindingCacheSynchronizer(
                runtimeBindingService,
                eventIoScheduler);
    }

    @Bean
    ChatEventCommitCoordinator chatEventCommitCoordinator(
            SessionApplicationService sessionService,
            ChatRunApplicationService chatRunService,
            ChatStreamApplicationService chatStreamService,
            ChatInteractionApplicationService chatInteractionService,
            RuntimeBindingApplicationService runtimeBindingService,
            ChatRunCompletionCoordinator completionCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            CommittedChatEventObserver committedEventObserver,
            AmbiguousRouteWaitPolicy ambiguousRouteWaitPolicy,
            RouteSwitchConfirmationWaitPolicy routeSwitchConfirmationWaitPolicy,
            RelayQuestionnaireWaitPolicy relayQuestionnaireWaitPolicy) {
        return new ChatEventCommitCoordinator(
                sessionService,
                chatRunService,
                chatStreamService,
                chatInteractionService,
                runtimeBindingService,
                completionCoordinator,
                refusalCoordinator,
                committedEventObserver,
                ambiguousRouteWaitPolicy,
                routeSwitchConfirmationWaitPolicy,
                relayQuestionnaireWaitPolicy);
    }

    @Bean
    DomainAgentRefusalCommitCoordinator domainAgentRefusalCommitCoordinator(
            ChatRunTerminalCommitService terminalCommitService,
            DomainAgentRefusalCoordinator refusalCoordinator,
            ChatRunCompletionCoordinator completionCoordinator,
            ChatRunApplicationService chatRunService,
            CommittedChatEventObserver committedEventObserver,
            RuntimeBindingCacheSynchronizer cacheSynchronizer,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new DomainAgentRefusalCommitCoordinator(
                terminalCommitService,
                refusalCoordinator,
                completionCoordinator,
                chatRunService,
                committedEventObserver,
                cacheSynchronizer,
                eventIoScheduler,
                eventIoScheduler);
    }

    @Bean
    ChatRunExecutionGateCoordinator chatRunExecutionGateCoordinator(
            ChatRunStartCoordinator runStartCoordinator,
            ChatRunLeaseApplicationService chatRunLeaseService,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            ChatEventPipeline eventPipeline,
            RuntimePendingEventGuard pendingEventGuard,
            ChatRunOwnerStopFinalizer ownerStopFinalizer,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new ChatRunExecutionGateCoordinator(
                runStartCoordinator,
                chatRunLeaseService,
                runExecutionRegistry,
                eventPipeline,
                eventIoScheduler,
                pendingEventGuard,
                ownerStopFinalizer);
    }

    @Bean
    ChatRunOwnerStopFinalizer chatRunOwnerStopFinalizer(
            ChatRunStopTerminalFinalizer terminalFinalizer,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            RuntimePendingEventGuard pendingEventGuard,
            RuntimeStreamLimitsProperties streamLimits,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new ChatRunOwnerStopFinalizer(
                terminalFinalizer, runExecutionRegistry, pendingEventGuard, eventIoScheduler, streamLimits);
    }

    @Bean
    ChatRunStopAssistantProjector chatRunStopAssistantProjector(
            SessionApplicationService sessionService,
            IdGenerator idGenerator) {
        return new ChatRunStopAssistantProjector(sessionService, idGenerator);
    }

    @Bean
    ChatRunStopTerminalFinalizer chatRunStopTerminalFinalizer(
            ChatRunStopAssistantProjector assistantProjector,
            ChatRunTerminalCommitService terminalCommitService,
            ChatRunApplicationService chatRunService,
            ChatStreamApplicationService chatStreamService) {
        return new ChatRunStopTerminalFinalizer(
                assistantProjector, terminalCommitService, chatRunService, chatStreamService);
    }

    @Bean
    RunStopOwnerCoordinator runStopOwnerCoordinator(
            com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus controlBus,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            ChatRunLeaseApplicationService leaseService,
            ChatRunApplicationService chatRunService,
            AgentRuntimeExecutor runtimeExecutor,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new RunStopOwnerCoordinator(
                controlBus, runExecutionRegistry, leaseService, chatRunService, runtimeExecutor, eventIoScheduler);
    }

    @Bean
    ChatEventPersistenceCoordinator chatEventPersistenceCoordinator(
            ChatEventPipeline eventPipeline,
            ChatRunExecutionGateCoordinator executionGateCoordinator,
            ChatEventCommitCoordinator eventCommitCoordinator,
            DomainAgentRefusalCommitCoordinator refusalCommitCoordinator) {
        return new ChatEventPersistenceCoordinator(
                eventPipeline,
                executionGateCoordinator,
                eventCommitCoordinator,
                refusalCommitCoordinator);
    }

    @Bean
    ChatRunFailureCoordinator chatRunFailureCoordinator(
            ChatRunTerminalCommitService terminalCommitService,
            ChatRunApplicationService chatRunService,
            ChatStreamApplicationService chatStreamService,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            LocalChatRunExecutionRegistry runExecutionRegistry) {
        return new ChatRunFailureCoordinator(
                terminalCommitService,
                chatRunService,
                chatStreamService,
                eventPersistenceCoordinator,
                runExecutionRegistry);
    }
}
