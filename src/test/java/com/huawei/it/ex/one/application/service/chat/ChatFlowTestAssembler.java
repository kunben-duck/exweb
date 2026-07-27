package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Assembles the extracted coordinators for characterization tests. */
final class ChatFlowTestAssembler {
    private final FinanceChatOrchestrator orchestrator;
    private final ChatRunAdmissionCoordinator admissionCoordinator;
    private final RuntimeBindingCacheSynchronizer cacheSynchronizer;
    private final DomainAgentRefusalCommitCoordinator refusalCommitCoordinator;
    private final ChatEventPipeline eventPipeline;

    ChatFlowTestAssembler(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentFacade documentFacade,
            ChatStreamApplicationService chatStreamService,
            ChatRunApplicationService chatRunService,
            ChatRunLeaseApplicationService chatRunLeaseService,
            ChatDeltaCoalescer chatDeltaCoalescer,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            RunAdmissionControlService runAdmissionControl,
            ChatRunStopCoordinator stopCoordinator,
            ChatInteractionApplicationService chatInteractionService,
            ChatRunTerminalCommitService terminalCommitService,
            IdGenerator idGenerator,
            Scheduler eventIoScheduler,
            DomainAgentProperties domainAgentProperties,
            RouteMemoryApplicationService routeMemoryService,
            ChatRunOperationalProperties runOperationalProperties) {
        Scheduler eventScheduler = eventIoScheduler == null
                ? Schedulers.boundedElastic()
                : eventIoScheduler;
        DomainAgentProperties agentProperties = domainAgentProperties == null
                ? new DomainAgentProperties()
                : domainAgentProperties;
        ChatRunOperationalProperties operationalProperties =
                runOperationalProperties == null
                        ? new ChatRunOperationalProperties()
                        : runOperationalProperties;
        RunMemoryContextAssembler memoryAssembler =
                new RunMemoryContextAssembler(memoryService);
        IntentClarificationContextAssembler clarificationAssembler =
                new IntentClarificationContextAssembler();
        InteractionEventFactory interactionEventFactory =
                new InteractionEventFactory();
        AppliedRouteRecorder routeRecorder = new AppliedRouteRecorder(
                intentRecognitionRecordService, routeMemoryService, chatRunService);
        RouteResolutionCoordinator routeResolutionCoordinator =
                new RouteResolutionCoordinator(
                        runtimeBindingService, routeSignalService, documentFacade);
        DomainAgentRefusalCoordinator refusalCoordinator =
                new DomainAgentRefusalCoordinator(
                        agentRuntimeExecutor,
                        routeSignalService,
                        runtimeBindingService,
                        agentProperties,
                        routeRecorder,
                        routeResolutionCoordinator,
                        chatRunLeaseService,
                        eventScheduler);
        FirstEventTimeoutCompensator timeoutCompensator =
                new FirstEventTimeoutCompensator(
                        chatInteractionService,
                        terminalCommitService,
                        chatRunService,
                        chatStreamService,
                        runExecutionRegistry,
                        eventScheduler);
        ChatRunStartCoordinator runStartCoordinator =
                new ChatRunStartCoordinator(
                        idGenerator,
                        runAdmissionControl,
                        runExecutionRegistry,
                        operationalProperties,
                        timeoutCompensator);
        InteractionContinuationCoordinator interactionContinuationCoordinator =
                new InteractionContinuationCoordinator(
                        runStartCoordinator,
                        chatInteractionService,
                        sessionService,
                        documentFacade,
                        clarificationAssembler);
        this.admissionCoordinator = new ChatRunAdmissionCoordinator(
                sessionService, chatRunService, chatInteractionService);
        ChatRunCompletionCoordinator completionCoordinator =
                new ChatRunCompletionCoordinator(
                        chatInteractionService,
                        agentRuntimeExecutor,
                        idGenerator,
                        terminalCommitService,
                        chatStreamService,
                        runtimeBindingService,
                        routeMemoryService);
        this.eventPipeline = new ChatEventPipeline(
                chatDeltaCoalescer,
                eventScheduler,
                null,
                chatRunService,
                chatStreamService,
                runtimeBindingService,
                completionCoordinator);
        ChatRunExecutionTerminalMarker terminalMarker =
                new ChatRunExecutionTerminalMarker(chatRunLeaseService);
        CommittedChatEventObserver committedEventObserver =
                new CommittedChatEventObserver(
                        terminalMarker,
                        runtimeBindingService,
                        chatStreamService,
                        completionCoordinator);
        this.cacheSynchronizer = new RuntimeBindingCacheSynchronizer(
                runtimeBindingService, eventScheduler);
        ChatEventCommitCoordinator eventCommitCoordinator =
                new ChatEventCommitCoordinator(
                        sessionService,
                        chatRunService,
                        chatStreamService,
                        chatInteractionService,
                        runtimeBindingService,
                        completionCoordinator,
                        refusalCoordinator,
                        committedEventObserver);
        this.refusalCommitCoordinator =
                new DomainAgentRefusalCommitCoordinator(
                        terminalCommitService,
                        refusalCoordinator,
                        completionCoordinator,
                        chatRunService,
                        committedEventObserver,
                        cacheSynchronizer,
                        eventScheduler,
                        eventScheduler);
        ChatRunExecutionGateCoordinator executionGateCoordinator =
                new ChatRunExecutionGateCoordinator(
                        runStartCoordinator,
                        chatRunLeaseService,
                        runExecutionRegistry,
                        eventPipeline,
                        eventScheduler);
        ChatEventPersistenceCoordinator eventPersistenceCoordinator =
                new ChatEventPersistenceCoordinator(
                        eventPipeline,
                        executionGateCoordinator,
                        eventCommitCoordinator,
                        refusalCommitCoordinator);
        ChatRunFailureCoordinator failureCoordinator =
                new ChatRunFailureCoordinator(
                        terminalCommitService,
                        chatRunService,
                        chatStreamService,
                        eventPersistenceCoordinator,
                        runExecutionRegistry);
        ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator =
                new ChatRuntimeDispatchCoordinator(
                        routeSignalService,
                        eventPersistenceCoordinator,
                        interactionEventFactory,
                        routeRecorder,
                        routeResolutionCoordinator,
                        refusalCoordinator,
                        systemResponseExecutor,
                        agentRuntimeExecutor);
        InteractionRunCoordinator interactionRunCoordinator =
                interactionRunCoordinator(new InteractionAssembly(
                        sessionService,
                        chatInteractionService,
                        runtimeBindingService,
                        agentRuntimeExecutor,
                        chatRunService,
                        chatRunLeaseService,
                        runStartCoordinator,
                        failureCoordinator,
                        clarificationAssembler,
                        interactionEventFactory,
                        runtimeDispatchCoordinator,
                        eventPersistenceCoordinator,
                        routeRecorder,
                        refusalCoordinator));
        ChatRunExecutionCoordinator runExecutionCoordinator =
                standardRunCoordinator(new StandardRunAssembly(
                        sessionService,
                        memoryAssembler,
                        documentFacade,
                        chatInteractionService,
                        chatRunService,
                        idGenerator,
                        runStartCoordinator,
                        routeResolutionCoordinator,
                        runtimeDispatchCoordinator,
                        eventPersistenceCoordinator,
                        failureCoordinator,
                        runExecutionRegistry,
                        clarificationAssembler,
                        chatRunLeaseService));
        this.orchestrator = new FinanceChatOrchestrator(
                runStartCoordinator,
                interactionContinuationCoordinator,
                interactionRunCoordinator,
                runExecutionCoordinator,
                stopCoordinator);
    }

    FinanceChatOrchestrator orchestrator() {
        return orchestrator;
    }

    ChatRunAdmissionCoordinator admissionCoordinator() {
        return admissionCoordinator;
    }

    RuntimeBindingCacheSynchronizer cacheSynchronizer() {
        return cacheSynchronizer;
    }

    DomainAgentRefusalCommitCoordinator refusalCommitCoordinator() {
        return refusalCommitCoordinator;
    }

    ChatEventPipeline eventPipeline() {
        return eventPipeline;
    }

    void setRunAdmissionCommitService(
            ChatRunAdmissionCommitService runAdmissionCommitService) {
        admissionCoordinator.setCommitService(runAdmissionCommitService);
    }

    void setDomainAgentControlIoScheduler(
            Scheduler scheduler) {
        cacheSynchronizer.setScheduler(scheduler);
        refusalCommitCoordinator.setControlIoScheduler(scheduler);
    }

    void setChatEventBatcher(ChatEventBatcher chatEventBatcher) {
        eventPipeline.setBatcher(chatEventBatcher);
    }

    private InteractionRunCoordinator interactionRunCoordinator(
            InteractionAssembly inputs) {
        InteractionRunLifecycle lifecycle = new InteractionRunLifecycle(
                inputs.chatRunService(),
                inputs.leaseService(),
                inputs.runStartCoordinator(),
                inputs.failureCoordinator());
        IntentClarificationRunCoordinator clarificationRunCoordinator =
                new IntentClarificationRunCoordinator(
                        inputs.clarificationAssembler(),
                        inputs.eventFactory(),
                        lifecycle,
                        inputs.runtimeDispatchCoordinator(),
                        inputs.persistenceCoordinator(),
                        admissionCoordinator);
        RouteSwitchContinuationCoordinator routeSwitchCoordinator =
                new RouteSwitchContinuationCoordinator(
                        inputs.runtimeBindingService(),
                        lifecycle,
                        inputs.routeRecorder(),
                        inputs.eventFactory(),
                        inputs.persistenceCoordinator(),
                        inputs.refusalCoordinator(),
                        inputs.runtimeExecutor());
        RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator =
                new RuntimeInteractionContinuationCoordinator(
                        inputs.runtimeBindingService(),
                        inputs.runtimeExecutor(),
                        inputs.routeRecorder(),
                        inputs.eventFactory(),
                        lifecycle,
                        inputs.persistenceCoordinator());
        return new InteractionRunCoordinator(
                inputs.sessionService(),
                inputs.interactionService(),
                clarificationRunCoordinator,
                routeSwitchCoordinator,
                runtimeInteractionCoordinator);
    }

    private ChatRunExecutionCoordinator standardRunCoordinator(
            StandardRunAssembly inputs) {
        StandardRunInputPreparer inputPreparer = new StandardRunInputPreparer(
                inputs.sessionService(),
                inputs.memoryAssembler(),
                inputs.documentFacade(),
                inputs.interactionService(),
                inputs.chatRunService(),
                inputs.idGenerator(),
                inputs.runStartCoordinator(),
                admissionCoordinator);
        StandardRunAdmissionCoordinator standardAdmissionCoordinator =
                new StandardRunAdmissionCoordinator(
                        inputs.chatRunService(),
                        inputs.runStartCoordinator(),
                        cacheSynchronizer,
                        admissionCoordinator);
        StandardRunRuntimeCoordinator runtimeCoordinator =
                new StandardRunRuntimeCoordinator(
                        inputs.clarificationAssembler(),
                        inputs.routeResolutionCoordinator(),
                        inputs.runtimeDispatchCoordinator(),
                        inputs.persistenceCoordinator(),
                        inputs.failureCoordinator(),
                        inputs.runExecutionRegistry());
        return new ChatRunExecutionCoordinator(
                inputPreparer,
                standardAdmissionCoordinator,
                runtimeCoordinator,
                inputs.leaseService(),
                inputs.runStartCoordinator(),
                inputs.failureCoordinator());
    }

    private record InteractionAssembly(
            SessionApplicationService sessionService,
            ChatInteractionApplicationService interactionService,
            RuntimeBindingApplicationService runtimeBindingService,
            AgentRuntimeExecutor runtimeExecutor,
            ChatRunApplicationService chatRunService,
            ChatRunLeaseApplicationService leaseService,
            ChatRunStartCoordinator runStartCoordinator,
            ChatRunFailureCoordinator failureCoordinator,
            IntentClarificationContextAssembler clarificationAssembler,
            InteractionEventFactory eventFactory,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator persistenceCoordinator,
            AppliedRouteRecorder routeRecorder,
            DomainAgentRefusalCoordinator refusalCoordinator
    ) {
    }

    private record StandardRunAssembly(
            SessionApplicationService sessionService,
            RunMemoryContextAssembler memoryAssembler,
            DocumentFacade documentFacade,
            ChatInteractionApplicationService interactionService,
            ChatRunApplicationService chatRunService,
            IdGenerator idGenerator,
            ChatRunStartCoordinator runStartCoordinator,
            RouteResolutionCoordinator routeResolutionCoordinator,
            ChatRuntimeDispatchCoordinator runtimeDispatchCoordinator,
            ChatEventPersistenceCoordinator persistenceCoordinator,
            ChatRunFailureCoordinator failureCoordinator,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            IntentClarificationContextAssembler clarificationAssembler,
            ChatRunLeaseApplicationService leaseService
    ) {
    }
}
