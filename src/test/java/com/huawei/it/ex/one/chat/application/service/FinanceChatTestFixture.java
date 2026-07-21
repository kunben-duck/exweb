package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.compat.FinanceChatCompatibilityAssembler;
import com.huawei.it.ex.one.chat.application.coordinator.FinanceChatOrchestrator;
import com.huawei.it.ex.one.chat.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.common.id.IdGenerator;
import com.huawei.it.ex.one.document.application.service.DocumentService;
import com.huawei.it.ex.one.intent.application.service.IntentRecognitionRecordService;
import com.huawei.it.ex.one.intent.application.service.MemoryApplicationService;
import com.huawei.it.ex.one.intent.application.service.RouteMemoryApplicationService;
import com.huawei.it.ex.one.intent.application.service.RouteSignalApplicationService;
import com.huawei.it.ex.one.runtime.application.service.AgentRuntimeExecutor;
import com.huawei.it.ex.one.runtime.application.service.DomainAgentExecutor;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.runtime.application.service.SystemResponseExecutor;
import com.huawei.it.ex.one.runtime.infrastructure.config.DomainAgentProperties;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Test-only replacement for the former hand-wired production constructors. */
final class FinanceChatTestFixture {
    private FinanceChatTestFixture() {
    }

    static FinanceEXChatService service(FinanceChatOrchestrator orchestrator) {
        return new FinanceEXChatService(orchestrator);
    }

    static FinanceEXChatService service(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentService documentService,
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
        return service(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, systemResponseExecutor, agentRuntimeExecutor, documentService,
                chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry,
                runAdmissionControl, stopCoordinator, chatInteractionService, terminalCommitService, idGenerator,
                eventIoScheduler, domainAgentProperties, routeMemoryService, runOperationalProperties, null, null);
    }

    static FinanceEXChatService service(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentService documentService,
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
            Scheduler domainAgentControlIoScheduler) {
        return service(dependencies(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, null, systemResponseExecutor, agentRuntimeExecutor, documentService,
                chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry,
                runAdmissionControl, stopCoordinator, chatInteractionService, terminalCommitService, idGenerator,
                eventIoScheduler, domainAgentProperties, routeMemoryService, runOperationalProperties,
                chatEventBatcher, domainAgentControlIoScheduler));
    }

    static FinanceEXChatService service(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentService documentService,
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
        return service(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, systemResponseExecutor, agentRuntimeExecutor, documentService,
                chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry,
                runAdmissionControl, stopCoordinator, chatInteractionService, terminalCommitService, idGenerator,
                eventIoScheduler, domainAgentProperties, routeMemoryService, new ChatRunOperationalProperties());
    }

    static FinanceEXChatService service(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentService documentService,
            ChatStreamApplicationService chatStreamService,
            ChatRunApplicationService chatRunService,
            ChatRunLeaseApplicationService chatRunLeaseService,
            ChatDeltaCoalescer chatDeltaCoalescer,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            RunAdmissionControlService runAdmissionControl,
            IdGenerator idGenerator) {
        return service(dependencies(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, null, systemResponseExecutor, agentRuntimeExecutor, documentService,
                chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer, runExecutionRegistry,
                runAdmissionControl, null, null, null, idGenerator, Schedulers.boundedElastic(),
                new DomainAgentProperties(), null, new ChatRunOperationalProperties(), null, null));
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
            DocumentService documentService,
            ChatStreamApplicationService chatStreamService,
            ChatRunApplicationService chatRunService,
            ChatRunLeaseApplicationService chatRunLeaseService,
            ChatDeltaCoalescer chatDeltaCoalescer,
            LocalChatRunExecutionRegistry runExecutionRegistry,
            RunAdmissionControlService runAdmissionControl,
            IdGenerator idGenerator) {
        return service(dependencies(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, domainAgentExecutor, systemResponseExecutor, agentRuntimeExecutor,
                documentService, chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer,
                runExecutionRegistry, runAdmissionControl, null, null, null, idGenerator,
                Schedulers.boundedElastic(), new DomainAgentProperties(), null,
                new ChatRunOperationalProperties(), null, null));
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
            DocumentService documentService,
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
        return service(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, domainAgentExecutor, systemResponseExecutor, agentRuntimeExecutor,
                documentService, chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer,
                runExecutionRegistry, runAdmissionControl, stopCoordinator, chatInteractionService,
                terminalCommitService, idGenerator, eventIoScheduler, domainAgentProperties, null,
                new ChatRunOperationalProperties(), null, null);
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
            DocumentService documentService,
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
            Scheduler domainAgentControlIoScheduler) {
        return service(dependencies(sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, domainAgentExecutor, systemResponseExecutor, agentRuntimeExecutor,
                documentService, chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer,
                runExecutionRegistry, runAdmissionControl, stopCoordinator, chatInteractionService,
                terminalCommitService, idGenerator, eventIoScheduler, domainAgentProperties, routeMemoryService,
                runOperationalProperties, chatEventBatcher, domainAgentControlIoScheduler));
    }

    private static FinanceEXChatService service(FinanceChatCompatibilityAssembler.Dependencies dependencies) {
        return service(FinanceChatCompatibilityAssembler.assemble(dependencies));
    }

    private static FinanceChatCompatibilityAssembler.Dependencies dependencies(
            SessionApplicationService sessionService,
            MemoryApplicationService memoryService,
            RuntimeBindingApplicationService runtimeBindingService,
            RouteSignalApplicationService routeSignalService,
            IntentRecognitionRecordService intentRecognitionRecordService,
            DomainAgentExecutor domainAgentExecutor,
            SystemResponseExecutor systemResponseExecutor,
            AgentRuntimeExecutor agentRuntimeExecutor,
            DocumentService documentService,
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
            Scheduler domainAgentControlIoScheduler) {
        return new FinanceChatCompatibilityAssembler.Dependencies(
                sessionService, memoryService, runtimeBindingService, routeSignalService,
                intentRecognitionRecordService, domainAgentExecutor, systemResponseExecutor, agentRuntimeExecutor,
                documentService, chatStreamService, chatRunService, chatRunLeaseService, chatDeltaCoalescer,
                runExecutionRegistry, runAdmissionControl, stopCoordinator, chatInteractionService,
                terminalCommitService, idGenerator, eventIoScheduler, domainAgentProperties, routeMemoryService,
                runOperationalProperties, chatEventBatcher, domainAgentControlIoScheduler);
    }
}
