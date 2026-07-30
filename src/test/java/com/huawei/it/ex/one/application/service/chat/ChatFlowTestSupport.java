package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.command.DocumentUpdateCommand;
import com.huawei.it.ex.one.application.command.DocumentUploadCommand;
import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.application.config.IntentRecordProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.AgentModeBindingContext;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatInteractionRequestRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunExecutionRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.integration.id.IdGenerateContext;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.integration.intent.IntentService;
import com.huawei.it.ex.one.application.integration.memory.ChatMessageRepository;
import com.huawei.it.ex.one.application.integration.memory.LongTermMemoryStore;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingCache;
import com.huawei.it.ex.one.application.integration.runtime.RuntimeBindingRepository;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.IntentRecognitionRecordService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessageAttachment;
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatMessagePartDraft;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunCancelSignal;
import com.huawei.it.ex.one.domain.chat.ChatRunExecution;
import com.huawei.it.ex.one.domain.chat.ChatRunExecutionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RunCompletedEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;
import com.huawei.it.ex.one.domain.chat.RunWaitingUserEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.document.DocumentLibraryPage;
import com.huawei.it.ex.one.domain.document.DocumentLibraryQuery;
import com.huawei.it.ex.one.domain.document.StoredObjectContent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.memory.LongTermMemoryItem;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.AgentModeSelection;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;
import com.huawei.it.ex.one.infrastructure.runtime.intentagent.BlockingIntentAgentRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
abstract class ChatFlowTestSupport {
    static int indexOfEvent(List<ChatEvent> events, String type, String sourceType) {
        for (int i = 0; i < events.size(); i++) {
            ChatEvent event = events.get(i);
            if (!type.equals(event.type())) {
                continue;
            }
            if (sourceType == null || (event.payload() != null
                    && sourceType.equals(event.payload().get("sourceType")))) {
                return i;
            }
        }
        return -1;
    }

    static BlockingIntentAgentRuntime intentAgent(IntentService intentService) {
        return new BlockingIntentAgentRuntime(intentService);
    }

    static RuntimeEvent domainAgentRefusalEvent(String runId, String sessionId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("source", "domain-agent");
        payload.put("sourceType", "agent.refusal");
        payload.put("metadataType", "domain_agent_control");
        payload.put("supervisorAction", "REROUTE");
        payload.put("type", "agent.refusal");
        payload.put("code", "FN-EX-CAHT-BIZ-DAG-001");
        payload.put("reasonCode", "OUT_OF_DOMAIN");
        payload.put("recoverable", false);
        payload.put("reason", "cannot answer this domain");
        return RuntimeEvent.metadata(runId, sessionId, payload);
    }

    RouteSignalApplicationService systemRouteService() {
        return new RouteSignalApplicationService(request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, user) -> null), new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
                return RouteSignalResult.of(RouteTarget.systemResponse("partial answer"));
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(routeInitial(request.user(), request.session(),
                        request.command(), request.attachments(), request.memory())));
            }
        };
    }

    FinanceEXChatService stopService(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                             InMemoryRunRepository runs, InMemoryEventStore events) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, new NeverCancelRunCache(), events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry);
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactionRequests, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, runtimeBindingRepository(), interactionService,
                Duration.ofDays(3));
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(noopRuntime(), limiter);
        ChatRunStopCoordinator stopCoordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                runtimeExecutor, interactionService, terminalCommitService, ids);
        return ChatFlowTestFixture.service(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                runtimeRouteService(),
                intentRecordService(),
                new SystemResponseExecutor(),
                runtimeExecutor,
                documentFacade(),
                streamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                null
        );
    }

    void seedRunningRun(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                InMemoryRunRepository runs, UserContext user,
                                String runId, String sessionId, String userMessageId) {
        Instant now = Instant.parse("2026-06-11T00:00:00Z");
        sessions.save(new ChatSession(sessionId, user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                userMessageId, sessionId, null, null, 1L, null, now, now));
        messages.save(new ChatMessage(userMessageId, user.tenantId(), user.ownerUserId(), sessionId,
                null, 1L, 0, 1, "user", "帮我处理一下", null, runId,
                "NORMAL", false, null, null, null, null, null, now));
        runs.save(new ChatRun(runId, user.tenantId(), user.ownerUserId(), sessionId, ChatRunStatus.RUNNING,
                "AGENT_RUNTIME", null, "relay", null, ChatRunMode.NEXT, null, userMessageId,
                null, null, null, null, now, null, Map.of(), now, now));
    }

    RouteSignalApplicationService runtimeRouteService() {
        return new RouteSignalApplicationService(request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, user) -> null), new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext user, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
                return RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"));
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(routeInitial(request.user(), request.session(),
                        request.command(), request.attachments(), request.memory())));
            }
        };
    }

    IntentRecognitionRecordService intentRecordService() {
        return new IntentRecognitionRecordService(new IntentRecordProperties(), record -> {
        }, new FixedIdGenerator(), new ObjectMapper(), Runnable::run);
    }

    DocumentFacade documentFacade() {
        return documentFacade(null);
    }

    DocumentFacade documentFacade(
            java.util.function.BiFunction<UserContext, List<AttachmentRef>, ResolvedChatAttachments> resolver) {
        return new DocumentFacade() {
            @Override public Mono<UploadedDocument> upload(UserContext user, DocumentUploadCommand command) { return Mono.empty(); }
            @Override public Mono<DocumentLibraryPage> list(UserContext user, DocumentLibraryQuery query) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> get(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> update(UserContext user, String documentId, DocumentUpdateCommand command) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> delete(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<com.huawei.it.ex.one.domain.document.DocumentDownload> prepareDownload(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<UploadedDocument> prepareAccess(UserContext user, String documentId) { return Mono.empty(); }
            @Override public Mono<StoredObjectContent> download(UserContext user, String documentId) { return Mono.empty(); }
            @Override public List<AttachmentRef> resolveAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) {
                return resolveChatAttachmentsForUser(user, attachments).attachments();
            }
            @Override public List<UploadedDocument> resolveDocumentsForUser(UserContext user, List<AttachmentRef> attachments) {
                return resolveChatAttachmentsForUser(user, attachments).documents();
            }
            @Override public ResolvedChatAttachments resolveChatAttachmentsForUser(UserContext user, List<AttachmentRef> attachments) {
                if (resolver != null) {
                    return resolver.apply(user, attachments);
                }
                if (attachments == null || attachments.isEmpty()) {
                    return ResolvedChatAttachments.empty();
                }
                Instant now = Instant.now();
                List<AttachmentRef> trusted = attachments.stream()
                        .map(attachment -> new AttachmentRef(
                                attachment.documentId(), "invoice.pdf", "application/pdf", 128L, 32L, "LOCAL_UPLOAD"))
                        .toList();
                List<UploadedDocument> documents = trusted.stream()
                        .map(attachment -> new UploadedDocument(
                                attachment.documentId(), user.tenantId(), user.ownerUserId(), null,
                                attachment.name(), "api-store", attachment.documentId(), attachment.contentType(),
                                attachment.sizeBytes(), "AVAILABLE", attachment.source(), attachment.tokenSize(),
                                "{\"providerDocument\":{\"providerLocatorType\":\"DOC_ID\","
                                        + "\"docId\":\"provider-" + attachment.documentId() + "\","
                                        + "\"docName\":\"" + attachment.name() + "\",\"docSize\":128}}",
                                now, now))
                        .toList();
                return new ResolvedChatAttachments(trusted, documents);
            }
            @Override public Map<String, Object> replaceRuntimeDocumentMetadata(Map<String, Object> metadata, List<UploadedDocument> documents) {
                Map<String, Object> copy = new LinkedHashMap<>(metadata == null ? Map.of() : metadata);
                Map<String, Object> scene = copy.get("sceneParam") instanceof Map<?, ?> value
                        ? new LinkedHashMap<>((Map<String, Object>) value)
                        : new LinkedHashMap<>();
                scene.remove("docList");
                if (documents != null && !documents.isEmpty()) {
                    scene.put("docList", documents.stream()
                            .map(document -> Map.<String, Object>of(
                                    "providerLocatorType", "DOC_ID",
                                    "docId", "provider-" + document.id(),
                                    "docName", document.originalName(),
                                    "docSize", document.sizeBytes()))
                            .toList());
                }
                if (!scene.isEmpty() || copy.containsKey("sceneParam")) {
                    copy.put("sceneParam", scene);
                }
                return Map.copyOf(copy);
            }
        };
    }

    DomainAgentExecutor domainAgentExecutor(DocumentFacade documentFacade, WorkloadConcurrencyLimiter limiter) {
        return new DomainAgentExecutor(new DomainAgentClient() {
            @Override public Flux<ChatEvent> query(DomainAgentRequest request) { return Flux.empty(); }
            @Override public Mono<Void> cancel(DomainAgentCancelRequest request) { return Mono.empty(); }
        }, documentFacade, limiter);
    }

    void awaitEvent(InMemoryEventStore events, String type) {
        awaitEventCount(events, type, 1);
    }

    void awaitEventCount(InMemoryEventStore events, String type, long expectedCount) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (events.events.stream().filter(event -> type.equals(event.type())).count() >= expectedCount) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for event " + type, ex);
            }
        }
        throw new AssertionError("Timed out waiting for " + expectedCount + " event(s) " + type + ", actual events="
                + events.events.stream().map(ChatEvent::type).toList());
    }

    <T> void awaitValue(AtomicReference<T> reference, T expected, String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (java.util.Objects.equals(reference.get(), expected)) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + label, ex);
            }
        }
        throw new AssertionError("Timed out waiting for " + label + ", expected=" + expected
                + ", actual=" + reference.get());
    }

    void awaitAtomicValue(AtomicInteger value, int expected, String label) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (value.get() == expected) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for " + label, ex);
            }
        }
        throw new AssertionError("Timed out waiting for " + label + ", expected=" + expected
                + ", actual=" + value.get());
    }

    ChatInteractionRequest awaitWaitingInteraction(InMemoryInteractionRequestRepository interactions,
                                                            String... excludedInteractionIds) {
        java.util.Set<String> excluded = excludedInteractionIds == null
                ? java.util.Set.of()
                : java.util.Set.of(excludedInteractionIds);
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<ChatInteractionRequest> waiting = interactions.requests.values().stream()
                    .filter(request -> !excluded.contains(request.id()))
                    .filter(request -> request.status() == ChatInteractionStatus.WAITING)
                    .findFirst();
            if (waiting.isPresent()) {
                return waiting.get();
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for route switch Interaction", ex);
            }
        }
        throw new AssertionError("Timed out waiting for another route switch Interaction, actual="
                + interactions.requests.values());
    }

    void awaitInteractionStatus(InMemoryInteractionRequestRepository interactions, String interactionId,
                                        ChatInteractionStatus status) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            ChatInteractionRequest request = interactions.requests.get(interactionId);
            if (request != null && request.status() == status) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for Interaction status " + status, ex);
            }
        }
        ChatInteractionRequest actual = interactions.requests.get(interactionId);
        throw new AssertionError("Timed out waiting for Interaction status " + status
                + ", actual=" + (actual == null ? null : actual.status())
                + ", continueRunId=" + (actual == null ? null : actual.continueRunId())
                + ", markWaitingForRunCalls=" + interactions.markWaitingForRunCalls.get());
    }

    RouteSignalApplicationService countingRuntimeRouteService(AtomicInteger routeCalls) {
        return new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.defer(() -> {
                    routeCalls.incrementAndGet();
                    return Flux.just(RouteSignalFrame.result(
                            RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"))));
                });
            }
        };
    }

    FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events) {
        return defaultFinanceService(sessions, messages, runs, events, runtimeRouteService());
    }

    FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, noopRuntime());
    }

    FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, runtime, true);
    }

    FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime,
                                                       boolean legacyDomainAgentCompatibility) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, runtime,
                legacyDomainAgentCompatibility, new NeverCancelRunCache());
    }

    FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime,
                                                       boolean legacyDomainAgentCompatibility,
                                                       ChatRunCache runCache) {
        return defaultFinanceService(sessions, messages, runs, events, routeService, runtime,
                legacyDomainAgentCompatibility, runCache, new InMemoryExecutionRepository());
    }

    FinanceEXChatService defaultFinanceService(InMemorySessionRepository sessions,
                                                       InMemoryMessageRepository messages,
                                                       InMemoryRunRepository runs,
                                                       InMemoryEventStore events,
                                                       RouteSignalApplicationService routeService,
                                                       AgentRuntime runtime,
                                                       boolean legacyDomainAgentCompatibility,
                                                       ChatRunCache runCache,
                                                       InMemoryExecutionRepository executions) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry
        );
        DocumentFacade documents = documentFacade();
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(runtime, limiter);
        if (!legacyDomainAgentCompatibility) {
            return ChatFlowTestFixture.service(
                    new SessionApplicationService(sessions, messages, ids, permissionChecker),
                    new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                    new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                            Duration.ofDays(3), "relay"),
                    routeService,
                    intentRecordService(),
                    new SystemResponseExecutor(),
                    runtimeExecutor,
                    documents,
                    new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                            permissionChecker, sessions,
                            new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                    new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                    leaseService,
                    new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                    executionRegistry,
                    new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                    ids
            );
        }
        return ChatFlowTestFixture.service(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                domainAgentExecutor(documents, limiter),
                new SystemResponseExecutor(),
                runtimeExecutor,
                documents,
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, runCache, events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                ids
        );
    }

    FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime) {
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                new InMemoryExecutionRepository());
    }

    FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions) {
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                executions, new ChatRunOperationalProperties());
    }

    FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions,
                                                                   ChatRunOperationalProperties runProperties) {
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                executions, runProperties, new NeverCancelRunCache());
    }

    FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions,
                                                                   ChatRunOperationalProperties runProperties,
                                                                   ChatRunCache runCache) {
        return financeServiceWithTerminalCommit(sessions, messages, runs, events, routeService, runtime,
                executions, runProperties, runCache, null);
    }

    FinanceEXChatService financeServiceWithTerminalCommit(InMemorySessionRepository sessions,
                                                                   InMemoryMessageRepository messages,
                                                                   InMemoryRunRepository runs,
                                                                   InMemoryEventStore events,
                                                                   RouteSignalApplicationService routeService,
                                                                   AgentRuntime runtime,
                                                                   InMemoryExecutionRepository executions,
                                                                   ChatRunOperationalProperties runProperties,
                                                                   ChatRunCache runCache,
                                                                   RouteMemoryApplicationService routeMemoryService) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        RuntimeBindingRepository bindings = runtimeBindingRepository();
        InMemoryInteractionRequestRepository interactionRequests = new InMemoryInteractionRequestRepository();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, runCache, events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions,
                (ApplicationInstanceIdProvider) () -> "instance-test",
                runProperties,
                ids,
                executionRegistry
        );
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactionRequests, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ofDays(3));
        AgentRuntimeExecutor runtimeExecutor = new AgentRuntimeExecutor(runtime, limiter);
        ChatRunStopCoordinator stopCoordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                runtimeExecutor, interactionService, terminalCommitService, ids);
        return ChatFlowTestFixture.service(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                new SystemResponseExecutor(),
                runtimeExecutor,
                documentFacade(),
                streamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(
                        new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                routeMemoryService,
                runProperties
        );
    }

    FinanceEXChatService financeServiceWithDomainClient(InMemorySessionRepository sessions,
                                                                InMemoryMessageRepository messages,
                                                                InMemoryRunRepository runs,
                                                                InMemoryEventStore events,
                                                                RouteSignalApplicationService routeService,
                                                                DomainAgentClient domainClient,
                                                                AgentRuntime relayRuntime) {
        IdGenerator ids = new FixedIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                new InMemoryExecutionRepository(),
                (ApplicationInstanceIdProvider) () -> "instance-test",
                new com.huawei.it.ex.one.application.config.ChatRunOperationalProperties(),
                ids,
                executionRegistry);
        DocumentFacade documents = documentFacade();
        return ChatFlowTestFixture.service(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(runtimeBindingRepository(), runtimeBindingCache(), ids,
                        Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                new DomainAgentExecutor(domainClient, documents, limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(relayRuntime, limiter),
                documents,
                new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                        permissionChecker, sessions,
                        new com.huawei.it.ex.one.application.config.ChatWebSocketProperties()),
                new ChatRunApplicationService(runs, new NeverCancelRunCache(), events, permissionChecker, sessions),
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                ids);
    }

    FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties) {
        return financeServiceWithDomainClientAndBindings(sessions, messages, runs, events, routeService,
                domainClient, relayRuntime, bindings, domainAgentProperties, liveEventBus());
    }

    FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus) {
        return financeServiceWithDomainClientAndBindings(sessions, messages, runs, events, routeService,
                domainClient, relayRuntime, bindings, domainAgentProperties, eventBus,
                new InMemoryInteractionRequestRepository());
    }

    FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus,
            InMemoryInteractionRequestRepository interactions) {
        return financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relayRuntime, bindings,
                domainAgentProperties, eventBus, interactions, runtimeBindingCache(), null);
    }

    FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus,
            InMemoryInteractionRequestRepository interactions,
            RuntimeBindingCache bindingCache,
            reactor.core.scheduler.Scheduler domainAgentControlIoScheduler) {
        return financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relayRuntime, bindings,
                domainAgentProperties, eventBus, interactions, bindingCache, domainAgentControlIoScheduler,
                documentFacade());
    }

    FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus,
            InMemoryInteractionRequestRepository interactions,
            RuntimeBindingCache bindingCache,
            reactor.core.scheduler.Scheduler domainAgentControlIoScheduler,
            DocumentFacade documents) {
        return financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relayRuntime, bindings,
                domainAgentProperties, eventBus, interactions, bindingCache, domainAgentControlIoScheduler,
                documents, new InMemoryExecutionRepository());
    }

    FinanceEXChatService financeServiceWithDomainClientAndBindings(
            InMemorySessionRepository sessions,
            InMemoryMessageRepository messages,
            InMemoryRunRepository runs,
            InMemoryEventStore events,
            RouteSignalApplicationService routeService,
            DomainAgentClient domainClient,
            AgentRuntime relayRuntime,
            RuntimeBindingRepository bindings,
            com.huawei.it.ex.one.application.config.DomainAgentProperties domainAgentProperties,
            ChatLiveEventBus eventBus,
            InMemoryInteractionRequestRepository interactions,
            RuntimeBindingCache bindingCache,
            reactor.core.scheduler.Scheduler domainAgentControlIoScheduler,
            DocumentFacade documents,
            InMemoryExecutionRepository executions) {
        IdGenerator ids = new SequentialIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        ChatRunOperationalProperties runProperties = new ChatRunOperationalProperties();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, ids, permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), eventBus, runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(
                runs, new NeverCancelRunCache(), events, permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions, (ApplicationInstanceIdProvider) () -> "instance-test", runProperties, ids,
                executionRegistry);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactions, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ZERO);
        RuntimeBindingApplicationService bindingService = new RuntimeBindingApplicationService(
                bindings, bindingCache, ids, Duration.ZERO, "relay");
        DomainAgentExecutor domainExecutor = new DomainAgentExecutor(domainClient, documents, limiter);
        AgentRuntimeExecutor relayExecutor = new AgentRuntimeExecutor(relayRuntime, limiter);
        ChatRunStopCoordinator stopCoordinator = new ChatRunStopCoordinator(
                sessionService, streamService, runService, leaseService, executionRegistry,
                relayExecutor, domainExecutor, ids);
        FinanceEXChatService service = ChatFlowTestFixture.service(
                sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                bindingService,
                routeService,
                intentRecordService(),
                domainExecutor,
                new SystemResponseExecutor(),
                relayExecutor,
                documents,
                streamService,
                runService,
                leaseService,
                new ChatDeltaCoalescer(new com.huawei.it.ex.one.application.config.ChatStreamProperties()),
                executionRegistry,
                new RunAdmissionControlService(
                        new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                domainAgentProperties);
        if (domainAgentControlIoScheduler != null) {
            service.setDomainAgentControlIoScheduler(domainAgentControlIoScheduler);
        }
        service.setRunAdmissionCommitService(
                new ChatRunAdmissionCommitService(sessionService, runService, interactionService, bindingService));
        return service;
    }

    RouteSignalApplicationService repeatedDomainAgentRouteService(AtomicInteger routeCalls,
                                                                           String domainAgentId) {
        return new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routeCalls.incrementAndGet();
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                        domainAgentId, "intent-agent", 1.0, "test intent route"))));
            }
        };
    }

    DomainAgentClient refusingDomainAgentClient() {
        return new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
    }

    AgentRuntime noopRuntime() {
        return new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) { return Flux.empty(); }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
    }

    ChatLiveEventBus liveEventBus() {
        return new ChatLiveEventBus() {
            @Override public void publish(String topicId, ChatEvent event) {}
            @Override public Flux<ChatEvent> subscribe(String topicId) { return Flux.never(); }
        };
    }

    LongTermMemoryStore longTermMemory() {
        return new LongTermMemoryStore() {
            @Override public List<LongTermMemoryItem> searchRelevant(String tenantId, String userId, String query, int topK) { return List.of(); }
            @Override public void save(LongTermMemoryItem item) {}
        };
    }

    RuntimeBindingRepository runtimeBindingRepository() {
        return new RuntimeBindingRepository() {
            @Override public Optional<RuntimeBinding> findById(String bindingId) { return Optional.empty(); }
            @Override public RuntimeBinding save(RuntimeBinding binding) { return binding; }
            @Override public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider) { return Optional.empty(); }
        };
    }

    RuntimeBindingCache runtimeBindingCache() {
        return new RuntimeBindingCache() {
            @Override public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) { return Optional.empty(); }
            @Override public void put(RuntimeBinding binding) {}
            @Override public void evict(String tenantId, String userId, String sessionId) {}
        };
    }

    static class CountingCancelRunCache implements ChatRunCache {
        final AtomicInteger checks = new AtomicInteger();
        @Override public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) { return Optional.empty(); }
        @Override public boolean tryClaimActive(ChatRun run) { return true; }
        @Override public void putActive(ChatRun run) {}
        @Override public void evictActive(String tenantId, String userId, String sessionId) {}
        @Override public void markCancellationRequested(String runId) {}
        @Override public ChatRunCancelSignal cancellationSignal(String runId) {
            return checks.incrementAndGet() >= 4 ? ChatRunCancelSignal.REQUESTED : ChatRunCancelSignal.NOT_REQUESTED;
        }
    }

    static class NeverCancelRunCache implements ChatRunCache {
        @Override public Optional<ChatRun> getActive(String tenantId, String userId, String sessionId) { return Optional.empty(); }
        @Override public boolean tryClaimActive(ChatRun run) { return true; }
        @Override public void putActive(ChatRun run) {}
        @Override public void evictActive(String tenantId, String userId, String sessionId) {}
        @Override public void markCancellationRequested(String runId) {}
        @Override public ChatRunCancelSignal cancellationSignal(String runId) {
            return ChatRunCancelSignal.NOT_REQUESTED;
        }
    }

    static final class BlockingPutRunCache extends NeverCancelRunCache {
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public void putActive(ChatRun run) {
            entered.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitPut() {
            try {
                return entered.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void releasePut() {
            release.countDown();
        }
    }

    static class AlwaysCancelledRunCache extends NeverCancelRunCache {
        @Override public ChatRunCancelSignal cancellationSignal(String runId) {
            return ChatRunCancelSignal.REQUESTED;
        }
    }

    static class InMemoryRunRepository implements ChatRunRepository {
        final Map<String, ChatRun> runs = new HashMap<>();
        final AtomicInteger ownerTerminalFenceAttempts = new AtomicInteger();
        boolean rejectOwnerTerminalFences;
        @Override public synchronized ChatRun save(ChatRun run) { runs.put(run.id(), run); return run; }
        @Override public synchronized boolean tryFenceOwnerTerminalCommit(OwnerTerminalFence fence) {
            ownerTerminalFenceAttempts.incrementAndGet();
            ChatRun current = runs.get(fence.runId());
            if (current == null || current.status() != ChatRunStatus.RUNNING) {
                return false;
            }
            if (rejectOwnerTerminalFences) {
                runs.put(current.id(), current.cancelling("TEST_STOP"));
                return false;
            }
            return true;
        }
        @Override public synchronized Optional<ChatRun> findById(String runId) { return Optional.ofNullable(runs.get(runId)); }
        @Override public Optional<ChatRun> findByTenantIdAndUserIdAndId(String tenantId, String userId, String runId) {
            return findById(runId).filter(run -> tenantId.equals(run.tenantId())).filter(run -> userId.equals(run.userId()));
        }
        @Override public Optional<ChatRun> findActiveBySession(String tenantId, String userId, String sessionId) { return Optional.empty(); }
    }

    static class FailingResolvedRouteRunRepository extends InMemoryRunRepository {
        @Override
        public ChatRun save(ChatRun run) {
            if (run != null && run.status() == ChatRunStatus.RUNNING && run.routeType() != null
                    && run.firstSeq() != null) {
                throw new IllegalStateException("route diagnostic db down");
            }
            return super.save(run);
        }
    }

    static final class FailingSecondStreamingObservationRunRepository extends InMemoryRunRepository {
        final AtomicInteger streamingObservationSaves = new AtomicInteger();

        @Override
        public ChatRun save(ChatRun run) {
            if (run != null && run.status() == ChatRunStatus.RUNNING
                    && run.firstSeq() != null && run.lastSeq() != null
                    && run.lastSeq() > run.firstSeq()
                    && streamingObservationSaves.incrementAndGet() == 2) {
                throw new IllegalStateException("run observation db down");
            }
            return super.save(run);
        }
    }

    static class InMemoryEventStore implements ChatEventStore {
        long seq;
        final List<ChatEvent> events = new CopyOnWriteArrayList<>();
        @Override public ChatEvent append(ChatEvent event) {
            ChatEvent stored = new StoredChatEvent(event.runId(), event.sessionId(), ++seq, event.type(), Instant.now(), event.payload());
            events.add(stored);
            return stored;
        }
        @Override public ChatEvent appendWithExecutionGuard(ChatEvent event, com.huawei.it.ex.one.domain.chat.RunExecutionClaim claim) { return append(event); }
        @Override public List<ChatEvent> findByOwnerAndSessionAfterSeq(String tenantId, String userId, String sessionId, long afterSeq) {
            return events.stream()
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }
        @Override public List<ChatEvent> findByOwnerAndRunAfterSeq(String tenantId, String userId, String sessionId, String runId, long afterSeq) {
            return events.stream()
                    .filter(event -> sessionId.equals(event.sessionId()))
                    .filter(event -> runId.equals(event.runId()))
                    .filter(event -> event.sequence() > afterSeq)
                    .toList();
        }
        @Override public long findLatestSeqByOwnerAndSession(String tenantId, String userId, String sessionId) { return seq; }
    }

    static class RejectingRunStartedEventStore extends InMemoryEventStore {
        final AtomicInteger guardedAttempts = new AtomicInteger();

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            guardedAttempts.incrementAndGet();
            if (event != null && "run.started".equals(event.type())) {
                throw new ChatEventAppendRejectedException("test fencing rejection");
            }
            return super.appendWithExecutionGuard(event, claim);
        }
    }

    static final class BlockingRunStartedEventStore extends InMemoryEventStore {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);

        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            if (event != null && "run.started".equals(event.type())) {
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw new ChatEventAppendRejectedException("test delayed run.started rejection");
            }
            return super.appendWithExecutionGuard(event, claim);
        }

        void releaseRunStarted() {
            release.countDown();
        }
    }

    static final class RejectingAutoSwitchEventStore extends InMemoryEventStore {
        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            if (event != null && event.payload() != null
                    && "AUTO_SWITCH".equals(event.payload().get("action"))) {
                throw new ChatEventAppendRejectedException("test auto-switch fencing rejection");
            }
            return super.appendWithExecutionGuard(event, claim);
        }
    }

    static class InMemoryExecutionRepository implements ChatRunExecutionRepository {
        final Map<String, ChatRunExecution> executions = new HashMap<>();
        volatile boolean rejectOwnerRunningChecks;

        @Override
        public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId, Duration leaseDuration) {
            Instant now = Instant.now();
            ChatRunExecution execution = new ChatRunExecution(executionId, run.id(), run.tenantId(), run.userId(),
                    run.sessionId(), ChatRunExecutionStatus.RUNNING, ownerInstanceId, now, now.plus(leaseDuration),
                    1L, null, null, 0, null, null, Map.of(), now, now);
            executions.put(run.id(), execution);
            return execution;
        }

        @Override public Optional<ChatRunExecution> findByRunId(String runId) { return Optional.ofNullable(executions.get(runId)); }
        @Override public boolean isCurrentOwnerRunning(RunExecutionClaim claim) {
            return !rejectOwnerRunningChecks && ChatRunExecutionRepository.super.isCurrentOwnerRunning(claim);
        }
        @Override public boolean heartbeat(String runId, String ownerInstanceId, long fencingToken,
                                           Duration leaseDuration) { return true; }
        @Override public boolean markTerminal(String runId, ChatRunExecutionStatus terminalStatus) {
            ChatRunExecution current = executions.get(runId);
            if (current == null || terminalStatus == null) {
                return false;
            }
            ChatRunExecution next = new ChatRunExecution(current.id(), current.runId(), current.tenantId(),
                    current.userId(), current.sessionId(), terminalStatus, current.ownerInstanceId(),
                    current.heartbeatAt(), current.leaseUntil(), current.fencingToken(),
                    current.recoveryStrategy(), current.recoveredByInstanceId(), current.recoveryAttempts(),
                    current.recoveryLeaseUntil(), current.runtimeResumeToken(), current.metadata(),
                    current.createdAt(), Instant.now());
            executions.put(runId, next);
            return true;
        }
        @Override public List<ChatRunExecution> findLeaseExpired(int limit) { return List.of(); }
        @Override public List<ChatRunExecution> findRecoveryExpired(int limit) { return List.of(); }
        @Override public Optional<ChatRunExecution> tryClaimRecovering(String runId, String recoveredByInstanceId, String strategy, Duration recoveryLeaseDuration) { return Optional.empty(); }
        @Override public Optional<ChatRunExecution> markTakeoverRunning(String runId, String ownerInstanceId, Duration leaseDuration) { return Optional.empty(); }
        @Override public boolean isLeaseExpired(String runId, Instant now) { return false; }
    }

    static final class FailingExecutionRepository extends InMemoryExecutionRepository {
        @Override
        public ChatRunExecution createForRun(ChatRun run, String executionId, String ownerInstanceId,
                                             Duration leaseDuration) {
            throw new IllegalStateException("execution db down");
        }
    }

    static class InMemorySessionRepository implements SessionRepository {
        final Map<String, ChatSession> sessions = new HashMap<>();
        @Override public Optional<ChatSession> findById(String sessionId) { return Optional.ofNullable(sessions.get(sessionId)); }
        @Override public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            return findById(sessionId).filter(session -> tenantId.equals(session.tenantId())).filter(session -> userId.equals(session.userId()));
        }
        @Override public List<ChatSession> findByTenantIdAndUserId(String tenantId, String userId) { return List.of(); }
        @Override public ChatSessionPage pageByTenantIdAndUserId(String tenantId, String userId, String cursor, int limit) {
            return new ChatSessionPage(List.of(), null);
        }
        @Override public ChatSession save(ChatSession session) { sessions.put(session.id(), session); return session; }
    }

    static final class BlockingSessionRepository extends InMemorySessionRepository {
        final java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        volatile boolean blockReads;

        @Override
        public Optional<ChatSession> findByTenantIdAndUserIdAndId(String tenantId, String userId, String sessionId) {
            if (blockReads) {
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException ex) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.findByTenantIdAndUserIdAndId(tenantId, userId, sessionId);
        }

        void blockReads() {
            blockReads = true;
        }

        void releaseReads() {
            release.countDown();
        }
    }

    static class CapturingRuntimeBindingRepository implements RuntimeBindingRepository {
        RuntimeBinding saved;
        final List<RuntimeBinding> savedHistory = new CopyOnWriteArrayList<>();

        @Override public Optional<RuntimeBinding> findById(String bindingId) {
            return Optional.ofNullable(saved).filter(binding -> binding.id().equals(bindingId));
        }
        @Override public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId, String provider) {
            return Optional.ofNullable(saved)
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE);
        }
        @Override public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId,
                                                                  String provider) {
            return findActive(tenantId, userId, sessionId, provider).stream().toList();
        }
        @Override public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
            return Optional.ofNullable(saved)
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                    .stream()
                    .toList();
        }
        @Override public List<RuntimeBinding> findResumableBySession(String tenantId, String userId, String sessionId,
                                                                     String provider) {
            return Optional.ofNullable(saved)
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.RESUMABLE)
                    .stream()
                    .toList();
        }
        @Override public RuntimeBinding save(RuntimeBinding binding) {
            saved = binding;
            savedHistory.add(binding);
            return binding;
        }
    }

    static class MultiBindingRuntimeBindingRepository implements RuntimeBindingRepository {
        final Map<String, RuntimeBinding> bindings = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public Optional<RuntimeBinding> findById(String bindingId) {
            return Optional.ofNullable(bindings.get(bindingId));
        }

        @Override
        public Optional<RuntimeBinding> findActive(String tenantId, String userId, String sessionId,
                                                   String provider) {
            return findActiveBySession(tenantId, userId, sessionId, provider).stream()
                    .max(Comparator.comparing(RuntimeBinding::updatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())));
        }

        @Override
        public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId,
                                                        String provider) {
            return matching(tenantId, userId, sessionId).stream()
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                    .toList();
        }

        @Override
        public List<RuntimeBinding> findActiveBySession(String tenantId, String userId, String sessionId) {
            return matching(tenantId, userId, sessionId).stream()
                    .filter(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                    .toList();
        }

        @Override
        public List<RuntimeBinding> findResumableBySession(String tenantId, String userId, String sessionId,
                                                           String provider) {
            return matching(tenantId, userId, sessionId).stream()
                    .filter(binding -> provider.equals(binding.provider()))
                    .filter(binding -> binding.status() == RuntimeBindingStatus.RESUMABLE)
                    .toList();
        }

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            bindings.put(binding.id(), binding);
            return binding;
        }

        List<RuntimeBinding> bindingsForProvider(String provider) {
            return bindings.values().stream()
                    .filter(binding -> provider.equals(binding.provider()))
                    .sorted(Comparator.comparing(RuntimeBinding::createdAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
        }

        List<RuntimeBinding> matching(String tenantId, String userId, String sessionId) {
            return bindings.values().stream()
                    .filter(binding -> tenantId.equals(binding.tenantId()))
                    .filter(binding -> userId.equals(binding.userId()))
                    .filter(binding -> sessionId.equals(binding.chatSessionId()))
                    .toList();
        }
    }

    static final class OwnerRejectingReplacementBindingRepository
            extends MultiBindingRuntimeBindingRepository {
        private final InMemoryExecutionRepository executions;
        private final AtomicInteger activeDomainAgentSaves = new AtomicInteger();
        final AtomicReference<RuntimeBindingStatus> replacementStatus = new AtomicReference<>();
        private volatile String replacementBindingId;

        OwnerRejectingReplacementBindingRepository(InMemoryExecutionRepository executions) {
            this.executions = executions;
        }

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            RuntimeBinding saved = super.save(binding);
            if ("domain-agent".equals(binding.provider())
                    && binding.status() == RuntimeBindingStatus.ACTIVE
                    && activeDomainAgentSaves.incrementAndGet() == 2) {
                replacementBindingId = binding.id();
                replacementStatus.set(binding.status());
                executions.rejectOwnerRunningChecks = true;
            } else if (binding.id().equals(replacementBindingId)) {
                replacementStatus.set(binding.status());
            }
            return saved;
        }
    }

    static final class TrackingReplacementBindingRepository
            extends MultiBindingRuntimeBindingRepository {
        private final AtomicInteger activeDomainAgentSaves = new AtomicInteger();
        final AtomicReference<RuntimeBindingStatus> replacementStatus = new AtomicReference<>();
        private volatile String replacementBindingId;

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            RuntimeBinding saved = super.save(binding);
            if ("domain-agent".equals(binding.provider())
                    && binding.status() == RuntimeBindingStatus.ACTIVE
                    && activeDomainAgentSaves.incrementAndGet() == 2) {
                replacementBindingId = binding.id();
                replacementStatus.set(binding.status());
            } else if (binding.id().equals(replacementBindingId)) {
                replacementStatus.set(binding.status());
            }
            return saved;
        }
    }

    static final class RetryOnceReplacementBindingRepository
            extends MultiBindingRuntimeBindingRepository {
        private final AtomicInteger activeDomainAgentSaves = new AtomicInteger();
        final AtomicInteger cancellationAttempts = new AtomicInteger();
        final AtomicReference<RuntimeBindingStatus> replacementStatus = new AtomicReference<>();
        private volatile String replacementBindingId;

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            RuntimeBinding saved = super.save(binding);
            if ("domain-agent".equals(binding.provider())
                    && binding.status() == RuntimeBindingStatus.ACTIVE
                    && activeDomainAgentSaves.incrementAndGet() == 2) {
                replacementBindingId = binding.id();
                replacementStatus.set(binding.status());
            } else if (binding.id().equals(replacementBindingId)) {
                replacementStatus.set(binding.status());
            }
            return saved;
        }

        @Override
        public boolean cancelActiveForRun(String bindingId, String runId) {
            if (bindingId.equals(replacementBindingId)
                    && cancellationAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("test transient binding cleanup failure");
            }
            return super.cancelActiveForRun(bindingId, runId);
        }
    }

    static final class ThreadCapturingRuntimeBindingRepository
            extends MultiBindingRuntimeBindingRepository {
        final AtomicReference<String> cancellationThread = new AtomicReference<>();

        @Override
        public RuntimeBinding save(RuntimeBinding binding) {
            if (binding != null && binding.status() == RuntimeBindingStatus.CANCELLED) {
                cancellationThread.compareAndSet(null, Thread.currentThread().getName());
            }
            return super.save(binding);
        }
    }

    static final class BlockingRuntimeBindingCache implements RuntimeBindingCache {
        final AtomicInteger puts = new AtomicInteger();
        final AtomicBoolean blockingEvictionClaimed = new AtomicBoolean(false);
        final java.util.concurrent.CountDownLatch evictionStarted = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseEviction = new java.util.concurrent.CountDownLatch(1);

        @Override
        public Optional<RuntimeBinding> get(String tenantId, String userId, String sessionId) {
            return Optional.empty();
        }

        @Override
        public void put(RuntimeBinding binding) {
            puts.incrementAndGet();
        }

        @Override
        public void evict(String tenantId, String userId, String sessionId) {
            if (puts.get() <= 0 || !blockingEvictionClaimed.compareAndSet(false, true)) {
                return;
            }
            evictionStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseEviction.await();
                    break;
                } catch (InterruptedException ex) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitEvictionStarted() {
            try {
                return evictionStarted.await(2, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void releaseEviction() {
            releaseEviction.countDown();
        }
    }

    static class InMemoryInteractionRequestRepository implements ChatInteractionRequestRepository {
        final Map<String, ChatInteractionRequest> requests = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicInteger markWaitingForRunCalls = new AtomicInteger();
        final AtomicInteger claimCalls = new AtomicInteger();

        @Override public ChatInteractionRequest insert(ChatInteractionRequest request) {
            requests.put(request.id(), request);
            return request;
        }
        @Override public Optional<ChatInteractionRequest> findByOwnerAndId(String tenantId, String userId, String interactionId) {
            return Optional.ofNullable(requests.get(interactionId))
                    .filter(request -> tenantId.equals(request.tenantId()))
                    .filter(request -> userId.equals(request.userId()));
        }
        @Override public Optional<ChatInteractionRequest> findWaitingBySession(String tenantId, String userId, String sessionId) {
            return requests.values().stream()
                    .filter(request -> tenantId.equals(request.tenantId()))
                    .filter(request -> userId.equals(request.userId()))
                    .filter(request -> sessionId.equals(request.sessionId()))
                    .filter(ChatInteractionRequest::waiting)
                    .findFirst();
        }
        @Override public boolean claimInteractionResponse(ChatInteractionClaimCommand command) {
            claimCalls.incrementAndGet();
            ChatInteractionRequest current = requests.get(command.interactionId());
            if (current == null || !command.tenantId().equals(current.tenantId())
                    || !command.userId().equals(current.userId()) || !current.waiting()) {
                return false;
            }
            requests.put(current.id(), new ChatInteractionRequest(current.id(), current.tenantId(), current.userId(),
                    current.sessionId(), current.sourceRunId(), command.continueRunId(), current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(), ChatInteractionStatus.RESPONDING,
                    current.requestPayload(), command.responsePayload(), current.expiresAt(), current.answeredAt(),
                    current.cancelledAt(), current.createdAt(), command.now()));
            return true;
        }
        @Override public int markAnswered(String tenantId, String userId, String interactionId, Instant answeredAt) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())) {
                return 0;
            }
            requests.put(interactionId, new ChatInteractionRequest(current.id(), current.tenantId(), current.userId(),
                    current.sessionId(), current.sourceRunId(), current.continueRunId(), current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(), ChatInteractionStatus.ANSWERED,
                    current.requestPayload(), current.responsePayload(), current.expiresAt(), answeredAt,
                    current.cancelledAt(), current.createdAt(), Instant.now()));
            return 1;
        }
        @Override public int markWaiting(String tenantId, String userId, String interactionId) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())) {
                return 0;
            }
            requests.put(interactionId, new ChatInteractionRequest(current.id(), current.tenantId(), current.userId(),
                    current.sessionId(), current.sourceRunId(), null, current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(), ChatInteractionStatus.WAITING,
                    current.requestPayload(), current.responsePayload(), current.expiresAt(), current.answeredAt(),
                    current.cancelledAt(), current.createdAt(), Instant.now()));
            return 1;
        }
        @Override public int markWaitingForRun(String tenantId, String userId, String interactionId,
                                               String continueRunId) {
            markWaitingForRunCalls.incrementAndGet();
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                    || current.status() != ChatInteractionStatus.RESPONDING
                    || !continueRunId.equals(current.continueRunId())) {
                return 0;
            }
            return markWaiting(tenantId, userId, interactionId);
        }
        @Override
        public int cancelOpenBySession(String tenantId, String userId, String sessionId, Instant cancelledAt) {
            int cancelled = 0;
            for (ChatInteractionRequest current : List.copyOf(requests.values())) {
                if (!tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                        || !sessionId.equals(current.sessionId())
                        || (current.status() != ChatInteractionStatus.WAITING
                        && current.status() != ChatInteractionStatus.RESPONDING)) {
                    continue;
                }
                requests.put(current.id(), new ChatInteractionRequest(
                        current.id(), current.tenantId(), current.userId(), current.sessionId(),
                        current.sourceRunId(), current.continueRunId(), current.userMessageId(),
                        current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                        current.runtimeSessionId(), current.approvalId(), current.interactionType(),
                        ChatInteractionStatus.CANCELLED, current.requestPayload(), current.responsePayload(),
                        current.expiresAt(), current.answeredAt(), cancelledAt, current.createdAt(), cancelledAt));
                cancelled++;
            }
            return cancelled;
        }
        @Override
        public int cancelWaitingById(String tenantId, String userId, String interactionId, Instant cancelledAt) {
            ChatInteractionRequest current = requests.get(interactionId);
            if (current == null || !tenantId.equals(current.tenantId()) || !userId.equals(current.userId())
                    || current.status() != ChatInteractionStatus.WAITING) {
                return 0;
            }
            requests.put(current.id(), new ChatInteractionRequest(
                    current.id(), current.tenantId(), current.userId(), current.sessionId(),
                    current.sourceRunId(), current.continueRunId(), current.userMessageId(),
                    current.assistantMessageId(), current.runtimeProvider(), current.runtimeBindingId(),
                    current.runtimeSessionId(), current.approvalId(), current.interactionType(),
                    ChatInteractionStatus.CANCELLED, current.requestPayload(), current.responsePayload(),
                    current.expiresAt(), current.answeredAt(), cancelledAt, current.createdAt(), cancelledAt));
            return 1;
        }
        @Override public int markExpired(String tenantId, String userId, String interactionId) { return 0; }
    }

    static class InMemoryMessageRepository implements ChatMessageRepository {
        final List<ChatMessage> messages = new ArrayList<>();
        final List<ChatMessagePart> parts = new ArrayList<>();
        final List<ChatMessageAttachment> attachments = new ArrayList<>();
        @Override public ChatMessage save(ChatMessage message) {
            messages.add(message);
            if (message.parts() != null) {
                parts.addAll(message.parts());
            }
            return message;
        }
        @Override public ChatMessage updateAssistantMessage(ChatMessage message) {
            for (int index = 0; index < messages.size(); index++) {
                ChatMessage existing = messages.get(index);
                if (!message.id().equals(existing.id())) {
                    continue;
                }
                List<ChatMessagePart> mergedParts = new ArrayList<>(
                        existing.parts() == null ? List.of() : existing.parts());
                if (message.parts() != null) {
                    mergedParts.addAll(message.parts());
                    parts.addAll(message.parts());
                }
                ChatMessage updated = message.withParts(List.copyOf(mergedParts));
                messages.set(index, updated);
                return updated;
            }
            throw new IllegalArgumentException("assistant 消息不存在: " + message.id());
        }
        @Override public List<ChatMessage> findRecentMessages(String tenantId, String userId, String sessionId, int limit) {
            return messages.stream().filter(message -> sessionId.equals(message.sessionId()))
                    .sorted(Comparator.comparing(ChatMessage::createdAt).reversed()).limit(limit).toList();
        }
        @Override public ChatMessagePage pageMessages(String tenantId, String userId, String sessionId, String cursor, int limit) {
            return new ChatMessagePage(messages, null);
        }
        @Override public Optional<ChatMessage> findByOwnerAndId(String tenantId, String userId, String messageId) {
            return messages.stream().filter(message -> messageId.equals(message.id())).findFirst();
        }
        @Override public ChatMessageAttachment saveAttachment(ChatMessageAttachment attachment) {
            attachments.add(attachment);
            return attachment;
        }
        @Override public List<ChatMessageAttachment> findAttachments(String tenantId, String userId, String messageId) {
            return attachments.stream().filter(attachment -> messageId.equals(attachment.messageId())).toList();
        }
    }

    static class FailingMessageRepository extends InMemoryMessageRepository {
        boolean failSaves;

        @Override
        public ChatMessage save(ChatMessage message) {
            if (failSaves) {
                throw new IllegalStateException("message db down");
            }
            return super.save(message);
        }
    }

    static final class CapturingRouteMemoryService extends RouteMemoryApplicationService {
        final List<RouteMemoryRouteCommand> routeDecisions = new CopyOnWriteArrayList<>();

        CapturingRouteMemoryService() {
            super(null, null, null);
        }

        @Override
        public void recordRouteDecision(RouteMemoryRouteCommand command) {
            routeDecisions.add(command);
        }
    }

    static class FixedIdGenerator implements IdGenerator {
        @Override public String newId(String prefix, IdGenerateContext context) { return prefix + "_1"; }
    }

    static class SequentialIdGenerator implements IdGenerator {
        final AtomicInteger sequence = new AtomicInteger();

        @Override
        public String newId(String prefix, IdGenerateContext context) {
            return prefix + "_" + sequence.incrementAndGet();
        }
    }
}
