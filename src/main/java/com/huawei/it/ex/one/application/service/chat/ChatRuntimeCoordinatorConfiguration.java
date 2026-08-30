package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceGate;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;

import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Declares Intent, Runtime and Interaction workflow components. */
@Configuration(proxyBeanMethods = false)
class ChatRuntimeCoordinatorConfiguration {
    @Bean
    RuntimeBindingDispatchCompensator runtimeBindingDispatchCompensator(
            RuntimeBindingApplicationService runtimeBindingService,
            DomainAgentProperties domainAgentProperties,
            @Qualifier("domainAgentControlIoScheduler") Scheduler controlIoScheduler) {
        return new RuntimeBindingDispatchCompensator(
                runtimeBindingService,
                controlIoScheduler,
                domainAgentProperties);
    }

    @Bean
    DomainAgentRefusalCoordinator domainAgentRefusalCoordinator(
            AgentRuntimeExecutor agentRuntimeExecutor,
            RouteSignalApplicationService routeSignalService,
            RuntimeBindingApplicationService runtimeBindingService,
            DomainAgentProperties domainAgentProperties,
            AppliedRouteRecorder appliedRouteRecorder,
            RouteResolutionCoordinator routeResolutionCoordinator,
            ChatRunLeaseApplicationService chatRunLeaseService,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler,
            @Qualifier("domainAgentControlIoScheduler") Scheduler controlIoScheduler,
            RuntimeBindingDispatchCompensator bindingCompensator,
            AgentDataPersistenceGate persistenceGate) {
        return new DomainAgentRefusalCoordinator(
                agentRuntimeExecutor,
                routeSignalService,
                runtimeBindingService,
                domainAgentProperties,
                appliedRouteRecorder,
                routeResolutionCoordinator,
                chatRunLeaseService,
                eventIoScheduler,
                controlIoScheduler,
                bindingCompensator,
                persistenceGate);
    }

    @Bean
    ChatRuntimeDispatchCoordinator chatRuntimeDispatchCoordinator(
            RouteSignalApplicationService routeSignalService,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            InteractionEventFactory interactionEventFactory,
            AppliedRouteRecorder appliedRouteRecorder,
            RouteResolutionCoordinator routeResolutionCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            RuntimeBindingDispatchCompensator bindingCompensator,
            AgentDataPersistenceGate persistenceGate) {
        return new ChatRuntimeDispatchCoordinator(
                routeSignalService,
                eventPersistenceCoordinator,
                interactionEventFactory,
                appliedRouteRecorder,
                routeResolutionCoordinator,
                refusalCoordinator,
                systemResponseExecutor,
                agentRuntimeExecutor,
                bindingCompensator,
                persistenceGate);
    }

    @Bean
    InteractionRunLifecycle interactionRunLifecycle(
            ChatRunApplicationService chatRunService,
            ChatRunLeaseApplicationService leaseService,
            ChatRunStartCoordinator runStartCoordinator,
            ChatRunFailureCoordinator failureCoordinator) {
        return new InteractionRunLifecycle(
                chatRunService,
                leaseService,
                runStartCoordinator,
                failureCoordinator);
    }

    @Bean
    IntentClarificationRunCoordinator intentClarificationRunCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory eventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator persistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator,
            RunMemoryContextAssembler memoryAssembler) {
        return new IntentClarificationRunCoordinator(
                clarificationAssembler,
                eventFactory,
                lifecycle,
                runtimeDispatchCoordinator,
                persistenceCoordinator,
                admissionCoordinator,
                memoryAssembler);
    }

    @Bean
    AmbiguousRouteContinuationCoordinator ambiguousRouteContinuationCoordinator(
            AmbiguousRouteSelectionResolver selectionResolver,
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory eventFactory,
            InteractionRunLifecycle lifecycle,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator persistenceCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator,
            RunMemoryContextAssembler memoryAssembler) {
        return new AmbiguousRouteContinuationCoordinator(
                selectionResolver,
                clarificationAssembler,
                eventFactory,
                lifecycle,
                runtimeDispatchCoordinator,
                persistenceCoordinator,
                admissionCoordinator,
                memoryAssembler);
    }

    @Bean
    RouteSwitchContinuationCoordinator routeSwitchContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            InteractionRunLifecycle lifecycle,
            AppliedRouteRecorder routeRecorder,
            InteractionEventFactory eventFactory,
            ChatEventPersistenceCoordinator persistenceCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            AgentRuntimeExecutor runtimeExecutor,
            AgentDataPersistenceGate persistenceGate,
            RunMemoryContextAssembler memoryAssembler,
            SensitiveInformationAccessNameResolver sensitiveInformationResolver,
            DocumentFacade documentFacade) {
        return new RouteSwitchContinuationCoordinator(
                runtimeBindingService,
                lifecycle,
                routeRecorder,
                eventFactory,
                persistenceCoordinator,
                refusalCoordinator,
                runtimeExecutor,
                persistenceGate,
                memoryAssembler,
                sensitiveInformationResolver,
                documentFacade);
    }

    @Bean
    RuntimeInteractionContinuationCoordinator runtimeInteractionContinuationCoordinator(
            RuntimeBindingApplicationService runtimeBindingService,
            AgentRuntimeExecutor runtimeExecutor,
            AppliedRouteRecorder routeRecorder,
            InteractionEventFactory eventFactory,
            InteractionRunLifecycle lifecycle,
            ChatEventPersistenceCoordinator persistenceCoordinator,
            @Qualifier("chatStreamEventScheduler") Scheduler eventIoScheduler) {
        return new RuntimeInteractionContinuationCoordinator(
                runtimeBindingService,
                runtimeExecutor,
                routeRecorder,
                eventFactory,
                lifecycle,
                persistenceCoordinator,
                eventIoScheduler);
    }

    @Bean
    InteractionRunCoordinator interactionRunCoordinator(
            SessionApplicationService sessionService,
            ChatInteractionApplicationService interactionService,
            IntentClarificationRunCoordinator clarificationRunCoordinator,
            AmbiguousRouteContinuationCoordinator ambiguousRouteCoordinator,
            RouteSwitchContinuationCoordinator routeSwitchCoordinator,
            RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator) {
        return new InteractionRunCoordinator(
                sessionService,
                interactionService,
                clarificationRunCoordinator,
                ambiguousRouteCoordinator,
                routeSwitchCoordinator,
                runtimeInteractionCoordinator);
    }

    @Bean
    StandardRunInputPreparer standardRunInputPreparer(
            SessionApplicationService sessionService,
            RunMemoryContextAssembler memoryAssembler,
            DocumentFacade documentFacade,
            ChatInteractionApplicationService interactionService,
            ChatRunApplicationService chatRunService,
            IdGenerator idGenerator,
            ChatRunStartCoordinator runStartCoordinator,
            ChatRunAdmissionCoordinator admissionCoordinator) {
        return new StandardRunInputPreparer(
                sessionService,
                memoryAssembler,
                documentFacade,
                interactionService,
                chatRunService,
                idGenerator,
                runStartCoordinator,
                admissionCoordinator);
    }

    @Bean
    StandardRunAdmissionCoordinator standardRunAdmissionCoordinator(
            ChatRunApplicationService chatRunService,
            ChatRunStartCoordinator runStartCoordinator,
            RuntimeBindingCacheSynchronizer cacheSynchronizer,
            ChatRunAdmissionCoordinator admissionCoordinator,
            ObjectProvider<SessionTitleApplicationService> sessionTitleServiceProvider) {
        return new StandardRunAdmissionCoordinator(
                chatRunService,
                runStartCoordinator,
                cacheSynchronizer,
                admissionCoordinator,
                sessionTitleServiceProvider.getIfAvailable());
    }

    @Bean
    StandardRunRuntimeCoordinator standardRunRuntimeCoordinator(
            IntentClarificationContextAssembler clarificationAssembler,
            RouteResolutionCoordinator routeResolutionCoordinator,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator persistenceCoordinator,
            ChatRunFailureCoordinator failureCoordinator,
            LocalChatRunExecutionRegistry runExecutionRegistry) {
        return new StandardRunRuntimeCoordinator(
                clarificationAssembler,
                routeResolutionCoordinator,
                runtimeDispatchCoordinator,
                persistenceCoordinator,
                failureCoordinator,
                runExecutionRegistry);
    }

    @Bean
    ChatRunExecutionCoordinator chatRunExecutionCoordinator(
            StandardRunInputPreparer inputPreparer,
            StandardRunAdmissionCoordinator admissionCoordinator,
            StandardRunRuntimeCoordinator runtimeCoordinator,
            ChatRunLeaseApplicationService leaseService,
            ChatRunStartCoordinator runStartCoordinator,
            ChatRunFailureCoordinator failureCoordinator) {
        return new ChatRunExecutionCoordinator(
                inputPreparer,
                admissionCoordinator,
                runtimeCoordinator,
                leaseService,
                runStartCoordinator,
                failureCoordinator);
    }

    @Bean
    FinanceChatOrchestrator financeChatOrchestrator(
            ChatRunStartCoordinator runStartCoordinator,
            InteractionContinuationCoordinator interactionContinuationCoordinator,
            InteractionRunCoordinator interactionRunCoordinator,
            ChatRunExecutionCoordinator runExecutionCoordinator,
            ChatRunStopCoordinator stopCoordinator) {
        return new FinanceChatOrchestrator(
                runStartCoordinator,
                interactionContinuationCoordinator,
                interactionRunCoordinator,
                runExecutionCoordinator,
                stopCoordinator);
    }
}
