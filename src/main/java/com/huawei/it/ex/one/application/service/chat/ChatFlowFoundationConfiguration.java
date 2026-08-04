package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;

import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares stateless context, admission and run-start workflow components. */
@Configuration(proxyBeanMethods = false)
class ChatFlowFoundationConfiguration {
    @Bean
    AmbiguousRouteSelectionResolver ambiguousRouteSelectionResolver(
            @Value("${financeex.intent.domain-expert-access-name:domain_expert}") String domainExpertAccessName) {
        return new AmbiguousRouteSelectionResolver(domainExpertAccessName);
    }

    @Bean
    AmbiguousRouteWaitPolicy ambiguousRouteWaitPolicy(
            AmbiguousRouteSelectionResolver selectionResolver,
            ObjectProvider<ChatInteractionProperties> interactionPropertiesProvider,
            @Value("${financeex.intent.ambiguous-route-wait-timeout:30s}") String timeout) {
        return new AmbiguousRouteWaitPolicy(
                selectionResolver,
                interactionPropertiesProvider.getIfAvailable(),
                DurationStyle.detectAndParse(timeout));
    }

    @Bean
    RouteSwitchConfirmationWaitPolicy routeSwitchConfirmationWaitPolicy(
            ObjectProvider<ChatInteractionProperties> interactionPropertiesProvider,
            @Value("${financeex.intent.ambiguous-route-wait-timeout:30s}") String timeout) {
        return new RouteSwitchConfirmationWaitPolicy(
                interactionPropertiesProvider.getIfAvailable(),
                DurationStyle.detectAndParse(timeout));
    }

    @Bean
    RelayQuestionnaireWaitPolicy relayQuestionnaireWaitPolicy(
            ObjectProvider<ChatInteractionProperties> interactionPropertiesProvider,
            @Value("${financeex.relay.questionnaire-wait-timeout:0s}") String timeout) {
        return new RelayQuestionnaireWaitPolicy(
                interactionPropertiesProvider.getIfAvailable(),
                DurationStyle.detectAndParse(timeout));
    }

    @Bean
    RunMemoryContextAssembler runMemoryContextAssembler(
            MemoryApplicationService memoryService) {
        return new RunMemoryContextAssembler(memoryService);
    }

    @Bean
    IntentClarificationContextAssembler intentClarificationContextAssembler() {
        return new IntentClarificationContextAssembler();
    }

    @Bean
    InteractionEventFactory interactionEventFactory() {
        return new InteractionEventFactory();
    }

    @Bean
    AppliedRouteRecorder appliedRouteRecorder(
            IntentRecognitionRecordService intentRecognitionRecordService,
            RouteMemoryApplicationService routeMemoryService,
            ChatRunApplicationService chatRunService) {
        return new AppliedRouteRecorder(
                intentRecognitionRecordService,
                routeMemoryService,
                chatRunService);
    }

    @Bean
    RouteResolutionCoordinator routeResolutionCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            DocumentFacade documentFacade) {
        return new RouteResolutionCoordinator(
                runtimeBindingService,
                routeSignalService,
                documentFacade);
    }

    @Bean
    FirstEventTimeoutCompensator firstEventTimeoutCompensator(
            ChatInteractionApplicationService interactionService,
            ChatRunTerminalCommitService terminalCommitService,
            ChatRunApplicationService runService,
            ChatStreamApplicationService streamService,
            LocalChatRunExecutionRegistry executionRegistry,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new FirstEventTimeoutCompensator(
                interactionService,
                terminalCommitService,
                runService,
                streamService,
                executionRegistry,
                eventIoScheduler);
    }

    @Bean
    ChatRunStartCoordinator chatRunStartCoordinator(
            IdGenerator idGenerator,
            RunAdmissionControlService admissionControl,
            LocalChatRunExecutionRegistry executionRegistry,
            ChatRunOperationalProperties operationalProperties,
            FirstEventTimeoutCompensator timeoutCompensator) {
        return new ChatRunStartCoordinator(
                idGenerator,
                admissionControl,
                executionRegistry,
                operationalProperties,
                timeoutCompensator);
    }

    @Bean
    InteractionContinuationCoordinator interactionContinuationCoordinator(
            ChatRunStartCoordinator runStartCoordinator,
            ChatInteractionApplicationService interactionService,
            SessionApplicationService sessionService,
            DocumentFacade documentFacade,
            IntentClarificationContextAssembler clarificationAssembler,
            AmbiguousRouteSelectionResolver ambiguousRouteSelectionResolver) {
        return new InteractionContinuationCoordinator(
                runStartCoordinator,
                interactionService,
                sessionService,
                documentFacade,
                clarificationAssembler,
                ambiguousRouteSelectionResolver);
    }

    @Bean
    ChatRunAdmissionCoordinator chatRunAdmissionCoordinator(
            SessionApplicationService sessionService,
            ChatRunApplicationService chatRunService,
            ChatInteractionApplicationService interactionService) {
        return new ChatRunAdmissionCoordinator(
                sessionService,
                chatRunService,
                interactionService);
    }
}
