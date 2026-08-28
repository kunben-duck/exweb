package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;

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
            ChatRunCompletionCoordinator completionCoordinator) {
        return new ChatEventPipeline(
                chatDeltaCoalescer,
                eventIoScheduler,
                null,
                chatRunService,
                chatStreamService,
                runtimeBindingService,
                completionCoordinator);
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
            RelayQuestionnaireWaitPolicy relayQuestionnaireWaitPolicy,
            DomainAgentAsyncTaskApplicationService asyncTaskService) {
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
                relayQuestionnaireWaitPolicy,
                asyncTaskService);
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
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new ChatRunExecutionGateCoordinator(
                runStartCoordinator,
                chatRunLeaseService,
                runExecutionRegistry,
                eventPipeline,
                eventIoScheduler);
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
