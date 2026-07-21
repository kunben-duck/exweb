package com.huawei.it.ex.one.chat.application.compat;

import com.huawei.it.ex.one.chat.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.runtime.infrastructure.config.DomainAgentProperties;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentControlPolicy;
import com.huawei.it.ex.one.document.application.service.DocumentService;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.intent.application.service.MemoryApplicationService;
import com.huawei.it.ex.one.intent.application.service.RouteMemoryApplicationService;
import com.huawei.it.ex.one.chat.application.coordinator.ChatEventCommitCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.ChatEventPersistenceCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.ChatEventPipeline;
import com.huawei.it.ex.one.chat.application.coordinator.ChatRunCompletionCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.ChatRunExecutionCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.ChatRunExecutionGateCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.ChatRunExecutionTerminalMarker;
import com.huawei.it.ex.one.chat.application.coordinator.ChatRunFailureCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.ChatRunStartCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.CommittedChatEventObserver;
import com.huawei.it.ex.one.chat.application.coordinator.DomainAgentRefusalCommitCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.DomainAgentRefusalCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.FinanceChatOrchestrator;
import com.huawei.it.ex.one.chat.application.coordinator.FirstEventTimeoutCompensator;
import com.huawei.it.ex.one.chat.application.coordinator.InteractionContinuationCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.InteractionEventFactory;
import com.huawei.it.ex.one.chat.application.coordinator.InteractionRunCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.InteractionRunLifecycle;
import com.huawei.it.ex.one.chat.application.coordinator.IntentClarificationContextAssembler;
import com.huawei.it.ex.one.chat.application.coordinator.IntentClarificationRunCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.IntentFlowCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.RouteDecisionRecorder;
import com.huawei.it.ex.one.chat.application.coordinator.RouteResolutionCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.RouteSwitchContinuationCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.RuntimeBindingCacheSynchronizer;
import com.huawei.it.ex.one.chat.application.coordinator.RuntimeInteractionContinuationCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.StandardRunAdmissionCoordinator;
import com.huawei.it.ex.one.chat.application.coordinator.StandardRunInputPreparer;
import com.huawei.it.ex.one.chat.application.coordinator.StandardRunRuntimeCoordinator;
import com.huawei.it.ex.one.chat.application.service.ChatDeltaCoalescer;
import com.huawei.it.ex.one.chat.application.service.ChatDocumentService;
import com.huawei.it.ex.one.chat.application.service.ChatEventBatcher;
import com.huawei.it.ex.one.chat.application.service.ChatInteractionApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunAdmissionCommitService;
import com.huawei.it.ex.one.chat.application.service.ChatRunLeaseApplicationService;
import com.huawei.it.ex.one.chat.application.service.ChatRunStopCoordinator;
import com.huawei.it.ex.one.chat.application.service.ChatRunTerminalCommitService;
import com.huawei.it.ex.one.chat.application.service.ChatStreamApplicationService;
import com.huawei.it.ex.one.chat.application.service.LocalChatRunExecutionRegistry;
import com.huawei.it.ex.one.chat.application.service.RunAdmissionControlService;
import com.huawei.it.ex.one.chat.application.service.SessionApplicationService;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRouteType;
import com.huawei.it.ex.one.runtime.application.model.RuntimeRunSnapshot;
import com.huawei.it.ex.one.intent.application.service.IntentDecisionService;
import com.huawei.it.ex.one.intent.application.service.IntentRecognitionRecordService;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentExecutionContext;
import com.huawei.it.ex.one.runtime.application.model.RuntimeExecutionContext;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.runtime.application.model.RuntimeInteractionResponseContext;
import com.huawei.it.ex.one.runtime.application.service.AgentRuntimeExecutor;
import com.huawei.it.ex.one.runtime.application.service.DomainAgentExecutor;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.runtime.application.service.SystemResponseExecutor;
import com.huawei.it.ex.one.common.concurrent.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.common.concurrent.ResourceIsolationProperties;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import java.util.List;

/**
 * Builds the former hand-wired service graph used by legacy unit-test constructors.
 * Production Spring wiring does not use this compatibility assembler.
 */
public final class FinanceChatCompatibilityAssembler {
    private FinanceChatCompatibilityAssembler() {
    }

    public static FinanceChatOrchestrator assemble(Dependencies dependencies) {
        Scheduler eventIoScheduler = dependencies.eventIoScheduler() == null
                ? Schedulers.boundedElastic()
                : dependencies.eventIoScheduler();
        Scheduler controlIoScheduler = dependencies.domainAgentControlIoScheduler() == null
                ? eventIoScheduler
                : dependencies.domainAgentControlIoScheduler();
        DomainAgentProperties domainAgentProperties = dependencies.domainAgentProperties() == null
                ? new DomainAgentProperties()
                : dependencies.domainAgentProperties();
        ChatRunOperationalProperties runProperties = dependencies.runOperationalProperties() == null
                ? new ChatRunOperationalProperties()
                : dependencies.runOperationalProperties();
        ChatDocumentService chatDocumentService = new ChatDocumentService(dependencies.documentFacade());
        AgentRuntimeExecutor runtimeExecutor = legacyCompatibleExecutor(
                dependencies.domainAgentExecutor(), dependencies.agentRuntimeExecutor());
        ChatRunStopCoordinator stopCoordinator = dependencies.stopCoordinator() == null
                ? new ChatRunStopCoordinator(
                        dependencies.sessionService(), dependencies.chatStreamService(),
                        dependencies.chatRunService(), dependencies.chatRunLeaseService(),
                        dependencies.runExecutionRegistry(), runtimeExecutor, dependencies.idGenerator())
                : dependencies.stopCoordinator();
        IntentClarificationContextAssembler clarificationAssembler =
                new IntentClarificationContextAssembler();
        FirstEventTimeoutCompensator timeoutCompensator = new FirstEventTimeoutCompensator(
                dependencies.chatInteractionService(), dependencies.terminalCommitService(),
                dependencies.chatRunService(), dependencies.chatStreamService(),
                dependencies.runExecutionRegistry(), eventIoScheduler);
        ChatRunStartCoordinator runStartCoordinator = new ChatRunStartCoordinator(
                dependencies.idGenerator(), dependencies.runAdmissionControl(),
                dependencies.runExecutionRegistry(), runProperties, timeoutCompensator);
        InteractionContinuationCoordinator interactionContinuationCoordinator =
                new InteractionContinuationCoordinator(
                        runStartCoordinator, dependencies.chatInteractionService(),
                        dependencies.sessionService(), chatDocumentService);
        ChatRunCompletionCoordinator completionCoordinator = new ChatRunCompletionCoordinator(
                dependencies.chatInteractionService(), runtimeExecutor, dependencies.idGenerator(),
                dependencies.terminalCommitService(), dependencies.chatStreamService(),
                dependencies.runtimeBindingService(), dependencies.routeMemoryService());
        ChatEventPipeline eventPipeline = new ChatEventPipeline(
                dependencies.chatDeltaCoalescer(), eventIoScheduler, dependencies.chatEventBatcher(),
                dependencies.chatRunService(), dependencies.chatStreamService(),
                dependencies.runtimeBindingService(), completionCoordinator);
        ChatRunExecutionGateCoordinator executionGateCoordinator =
                new ChatRunExecutionGateCoordinator(
                        runStartCoordinator, dependencies.chatRunLeaseService(),
                        dependencies.runExecutionRegistry(), eventPipeline, eventIoScheduler);
        RouteDecisionRecorder routeDecisionRecorder = new RouteDecisionRecorder(
                dependencies.intentRecognitionRecordService(), dependencies.routeMemoryService(),
                dependencies.chatRunService());
        RouteResolutionCoordinator routeResolutionCoordinator = new RouteResolutionCoordinator(
                dependencies.runtimeBindingService(), dependencies.intentDecisionService(),
                chatDocumentService);
        InteractionEventFactory interactionEventFactory = new InteractionEventFactory();
        DomainAgentRefusalCoordinator refusalCoordinator = new DomainAgentRefusalCoordinator(
                runtimeExecutor, dependencies.intentDecisionService(), dependencies.runtimeBindingService(),
                new DomainAgentControlPolicy(domainAgentProperties.normalizedMaxReroutes()),
                routeDecisionRecorder, routeResolutionCoordinator,
                executionGateCoordinator, eventIoScheduler);
        IntentFlowCoordinator intentFlowCoordinator = new IntentFlowCoordinator(
                dependencies.intentDecisionService(), executionGateCoordinator, interactionEventFactory,
                routeResolutionCoordinator, routeDecisionRecorder, refusalCoordinator,
                dependencies.systemResponseExecutor(), runtimeExecutor);
        ChatRunExecutionTerminalMarker executionTerminalMarker =
                new ChatRunExecutionTerminalMarker(dependencies.chatRunLeaseService());
        CommittedChatEventObserver committedEventObserver = new CommittedChatEventObserver(
                executionTerminalMarker, dependencies.runtimeBindingService(),
                dependencies.chatStreamService(), completionCoordinator);
        RuntimeBindingCacheSynchronizer cacheSynchronizer = new RuntimeBindingCacheSynchronizer(
                dependencies.runtimeBindingService(), controlIoScheduler);
        ChatEventCommitCoordinator eventCommitCoordinator = new ChatEventCommitCoordinator(
                dependencies.sessionService(), dependencies.chatRunService(), dependencies.chatStreamService(),
                dependencies.chatInteractionService(), dependencies.runtimeBindingService(),
                completionCoordinator, refusalCoordinator, committedEventObserver);
        DomainAgentRefusalCommitCoordinator refusalCommitCoordinator =
                new DomainAgentRefusalCommitCoordinator(
                        dependencies.terminalCommitService(), refusalCoordinator, completionCoordinator,
                        dependencies.chatRunService(), committedEventObserver, cacheSynchronizer,
                        controlIoScheduler, eventIoScheduler);
        ChatEventPersistenceCoordinator eventPersistenceCoordinator =
                new ChatEventPersistenceCoordinator(
                        eventPipeline, executionGateCoordinator, eventCommitCoordinator,
                        refusalCommitCoordinator);
        ChatRunAdmissionCommitService admissionCommitService = admissionCommitService(dependencies);
        ChatRunFailureCoordinator failureCoordinator = new ChatRunFailureCoordinator(
                dependencies.terminalCommitService(), dependencies.chatRunService(),
                dependencies.chatStreamService(), eventPersistenceCoordinator,
                dependencies.runExecutionRegistry());
        ChatRunExecutionCoordinator runExecutionCoordinator = standardRunCoordinator(
                dependencies, chatDocumentService, admissionCommitService, new StandardWorkflowDependencies(
                        runStartCoordinator, clarificationAssembler, routeResolutionCoordinator,
                        intentFlowCoordinator, eventPersistenceCoordinator, failureCoordinator,
                        cacheSynchronizer));
        InteractionRunCoordinator interactionRunCoordinator = interactionRunCoordinator(
                dependencies, admissionCommitService, new InteractionWorkflowDependencies(
                        runStartCoordinator, clarificationAssembler, intentFlowCoordinator,
                        eventPersistenceCoordinator, failureCoordinator, routeDecisionRecorder,
                        interactionEventFactory, executionGateCoordinator, refusalCoordinator,
                        runtimeExecutor));
        return new FinanceChatOrchestrator(
                runStartCoordinator, interactionContinuationCoordinator, interactionRunCoordinator,
                runExecutionCoordinator, stopCoordinator);
    }

    private static ChatRunExecutionCoordinator standardRunCoordinator(
            Dependencies dependencies, ChatDocumentService chatDocumentService,
            ChatRunAdmissionCommitService admissionCommitService,
            StandardWorkflowDependencies workflow) {
        StandardRunInputPreparer inputPreparer = new StandardRunInputPreparer(
                dependencies.sessionService(), dependencies.memoryService(), chatDocumentService,
                dependencies.chatInteractionService(), dependencies.chatRunService(), dependencies.idGenerator(),
                workflow.runStartCoordinator());
        StandardRunAdmissionCoordinator admissionCoordinator = new StandardRunAdmissionCoordinator(
                dependencies.chatRunService(), workflow.runStartCoordinator(),
                workflow.cacheSynchronizer(), admissionCommitService);
        StandardRunRuntimeCoordinator runtimeCoordinator = new StandardRunRuntimeCoordinator(
                workflow.clarificationAssembler(), workflow.routeResolutionCoordinator(),
                workflow.intentFlowCoordinator(), workflow.eventPersistenceCoordinator(),
                workflow.failureCoordinator(), dependencies.runExecutionRegistry());
        return new ChatRunExecutionCoordinator(
                inputPreparer, admissionCoordinator, runtimeCoordinator,
                dependencies.chatRunLeaseService(), workflow.runStartCoordinator(),
                workflow.failureCoordinator());
    }

    private static InteractionRunCoordinator interactionRunCoordinator(
            Dependencies dependencies, ChatRunAdmissionCommitService admissionCommitService,
            InteractionWorkflowDependencies workflow) {
        InteractionRunLifecycle lifecycle = new InteractionRunLifecycle(
                dependencies.chatRunService(), dependencies.chatRunLeaseService(),
                workflow.runStartCoordinator(), workflow.failureCoordinator());
        RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator =
                new RuntimeInteractionContinuationCoordinator(
                        dependencies.runtimeBindingService(), workflow.runtimeExecutor(),
                        workflow.routeDecisionRecorder(), workflow.interactionEventFactory(), lifecycle,
                        workflow.eventPersistenceCoordinator(), workflow.executionGateCoordinator());
        IntentClarificationRunCoordinator intentClarificationCoordinator =
                new IntentClarificationRunCoordinator(
                        workflow.clarificationAssembler(), workflow.interactionEventFactory(), lifecycle,
                        workflow.intentFlowCoordinator(), workflow.eventPersistenceCoordinator(),
                        admissionCommitService);
        RouteSwitchContinuationCoordinator routeSwitchCoordinator =
                new RouteSwitchContinuationCoordinator(
                        dependencies.runtimeBindingService(), lifecycle, workflow.routeDecisionRecorder(),
                        workflow.interactionEventFactory(), workflow.eventPersistenceCoordinator(),
                        workflow.executionGateCoordinator(), workflow.refusalCoordinator(),
                        workflow.runtimeExecutor());
        return new InteractionRunCoordinator(
                dependencies.sessionService(), dependencies.chatInteractionService(),
                intentClarificationCoordinator, routeSwitchCoordinator, runtimeInteractionCoordinator);
    }

    private static AgentRuntimeExecutor legacyCompatibleExecutor(DomainAgentExecutor domainAgentExecutor,
                                                                  AgentRuntimeExecutor delegate) {
        return domainAgentExecutor == null
                ? delegate
                : new LegacyDomainAgentAwareExecutor(delegate, domainAgentExecutor);
    }

    private static ChatRunAdmissionCommitService admissionCommitService(Dependencies dependencies) {
        ChatRunAdmissionCommitService service = new ChatRunAdmissionCommitService(
                dependencies.sessionService(), dependencies.chatRunService(),
                dependencies.chatInteractionService(), dependencies.runtimeBindingService());
        if (dependencies.chatInteractionService() != null) {
            return service;
        }
        return new ChatRunAdmissionCommitService(
                dependencies.sessionService(), dependencies.chatRunService(), null,
                dependencies.runtimeBindingService()) {
            @Override
            public AdmissionResult commitDirectDomainAgent(
                    UserContext user, ChatCommand command, ChatSession session,
                    String runId, List<AttachmentRef> attachments) {
                return commit(user, command, session, runId, attachments);
            }
        };
    }

    public record Dependencies(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            IntentDecisionService intentDecisionService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            DomainAgentExecutor domainAgentExecutor,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentService documentFacade,
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
            ChatRunOperationalProperties runOperationalProperties,
            ChatEventBatcher chatEventBatcher,
            Scheduler domainAgentControlIoScheduler
    ) {
    }

    private record StandardWorkflowDependencies(
            ChatRunStartCoordinator runStartCoordinator,
            IntentClarificationContextAssembler clarificationAssembler,
            RouteResolutionCoordinator routeResolutionCoordinator,
            IntentFlowCoordinator intentFlowCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunFailureCoordinator failureCoordinator,
            RuntimeBindingCacheSynchronizer cacheSynchronizer
    ) {
    }

    private record InteractionWorkflowDependencies(
            ChatRunStartCoordinator runStartCoordinator,
            IntentClarificationContextAssembler clarificationAssembler,
            IntentFlowCoordinator intentFlowCoordinator,
            ChatEventPersistenceCoordinator eventPersistenceCoordinator,
            ChatRunFailureCoordinator failureCoordinator,
            RouteDecisionRecorder routeDecisionRecorder,
            InteractionEventFactory interactionEventFactory,
            ChatRunExecutionGateCoordinator executionGateCoordinator,
            DomainAgentRefusalCoordinator refusalCoordinator,
            AgentRuntimeExecutor runtimeExecutor
    ) {
    }

    private static final class LegacyDomainAgentAwareExecutor extends AgentRuntimeExecutor {
        private final AgentRuntimeExecutor delegate;
        private final DomainAgentExecutor domainAgentExecutor;

        private LegacyDomainAgentAwareExecutor(AgentRuntimeExecutor delegate,
                                               DomainAgentExecutor domainAgentExecutor) {
            super((com.huawei.it.ex.one.runtime.application.client.AgentRuntime) null,
                    new WorkloadConcurrencyLimiter(new ResourceIsolationProperties()));
            this.delegate = delegate;
            this.domainAgentExecutor = domainAgentExecutor;
        }

        @Override
        public Flux<ChatEvent> execute(RuntimeExecutionContext context) {
            if (context != null && context.route() != null
                    && context.route().type() == RuntimeRouteType.DOMAIN_AGENT) {
                return domainAgentExecutor.execute(new DomainAgentExecutionContext(
                        context.command(), context.runId(), context.route(), context.user(),
                        context.binding(), context.forwardHeaders()));
            }
            return delegate.execute(context);
        }

        @Override
        public Flux<ChatEvent> continueWithUserResponse(RuntimeInteractionResponseContext context) {
            return delegate.continueWithUserResponse(context);
        }

        @Override
        public boolean supportsWaitingUserResponse(String runtimeProvider) {
            return delegate.supportsWaitingUserResponse(runtimeProvider);
        }

        @Override
        public Mono<Void> cancel(RuntimeRunSnapshot run, UserContext user, RuntimeForwardHeaders forwardHeaders) {
            if (run != null && RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(run.runtimeProvider())) {
                return domainAgentExecutor.cancel(run, user, forwardHeaders);
            }
            return delegate.cancel(run, user, forwardHeaders);
        }
    }
}
