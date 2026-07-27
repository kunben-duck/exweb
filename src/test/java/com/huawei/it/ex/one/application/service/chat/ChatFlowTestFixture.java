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
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;

import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Composes the legacy constructor variants used by characterization tests. */
final class ChatFlowTestFixture {
    private ChatFlowTestFixture() {
    }

    static FinanceEXChatService service(
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
            IdGenerator idGenerator) {
        return service(
                sessionService,
                memoryService,
                runtimeBindingService,
                routeSignalService,
                intentRecognitionRecordService,
                systemResponseExecutor,
                agentRuntimeExecutor,
                documentFacade,
                chatStreamService,
                chatRunService,
                chatRunLeaseService,
                chatDeltaCoalescer,
                runExecutionRegistry,
                runAdmissionControl,
                new ChatRunStopCoordinator(
                        sessionService,
                        chatStreamService,
                        chatRunService,
                        chatRunLeaseService,
                        runExecutionRegistry,
                        agentRuntimeExecutor,
                        idGenerator),
                null,
                null,
                idGenerator,
                Schedulers.boundedElastic(),
                new DomainAgentProperties(),
                null,
                new ChatRunOperationalProperties());
    }

    static FinanceEXChatService service(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            DomainAgentExecutor domainAgentExecutor,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentFacade documentFacade,
            ChatStreamApplicationService chatStreamService,
            ChatRunApplicationService chatRunService,
            ChatRunLeaseApplicationService chatRunLeaseService,
            ChatDeltaCoalescer chatDeltaCoalescer,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            RunAdmissionControlService runAdmissionControl,
            IdGenerator idGenerator) {
        return service(
                sessionService,
                memoryService,
                runtimeBindingService,
                routeSignalService,
                intentRecognitionRecordService,
                systemResponseExecutor,
                legacyCompatibleExecutor(domainAgentExecutor, agentRuntimeExecutor),
                documentFacade,
                chatStreamService,
                chatRunService,
                chatRunLeaseService,
                chatDeltaCoalescer,
                runExecutionRegistry,
                runAdmissionControl,
                idGenerator);
    }

    static FinanceEXChatService service(
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
            RouteMemoryApplicationService routeMemoryService) {
        return service(
                sessionService,
                memoryService,
                runtimeBindingService,
                routeSignalService,
                intentRecognitionRecordService,
                systemResponseExecutor,
                agentRuntimeExecutor,
                documentFacade,
                chatStreamService,
                chatRunService,
                chatRunLeaseService,
                chatDeltaCoalescer,
                runExecutionRegistry,
                runAdmissionControl,
                stopCoordinator,
                chatInteractionService,
                terminalCommitService,
                idGenerator,
                eventIoScheduler,
                domainAgentProperties,
                routeMemoryService,
                new ChatRunOperationalProperties());
    }

    static FinanceEXChatService service(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            DomainAgentExecutor domainAgentExecutor,
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
            DomainAgentProperties domainAgentProperties) {
        return service(
                sessionService,
                memoryService,
                runtimeBindingService,
                routeSignalService,
                intentRecognitionRecordService,
                systemResponseExecutor,
                legacyCompatibleExecutor(domainAgentExecutor, agentRuntimeExecutor),
                documentFacade,
                chatStreamService,
                chatRunService,
                chatRunLeaseService,
                chatDeltaCoalescer,
                runExecutionRegistry,
                runAdmissionControl,
                stopCoordinator,
                chatInteractionService,
                terminalCommitService,
                idGenerator,
                eventIoScheduler,
                domainAgentProperties,
                null,
                new ChatRunOperationalProperties());
    }

    static FinanceEXChatService service(
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
        ChatFlowTestAssembler assembler =
                new ChatFlowTestAssembler(
                        sessionService,
                        memoryService,
                        runtimeBindingService,
                        routeSignalService,
                        intentRecognitionRecordService,
                        systemResponseExecutor,
                        agentRuntimeExecutor,
                        documentFacade,
                        chatStreamService,
                        chatRunService,
                        chatRunLeaseService,
                        chatDeltaCoalescer,
                        runExecutionRegistry,
                        runAdmissionControl,
                        stopCoordinator,
                        chatInteractionService,
                        terminalCommitService,
                        idGenerator,
                        eventIoScheduler,
                        domainAgentProperties,
                        routeMemoryService,
                        runOperationalProperties);
        return new FinanceEXChatService(
                assembler.orchestrator(),
                assembler.admissionCoordinator(),
                assembler.cacheSynchronizer(),
                assembler.refusalCommitCoordinator(),
                assembler.eventPipeline());
    }

    private static AgentRuntimeExecutor legacyCompatibleExecutor(
            DomainAgentExecutor domainAgentExecutor,
            AgentRuntimeExecutor delegate) {
        return domainAgentExecutor == null
                ? delegate
                : new LegacyDomainAgentAwareExecutor(delegate, domainAgentExecutor);
    }
}
