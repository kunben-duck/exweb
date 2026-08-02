package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.memory.RouteMemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
class ChatIntentFlowTest extends ChatFlowTestSupport {
    @Test
    void intentCallingProgressIsPersistedBeforeIntentRouteCompletes() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "domain_agent_finance_knowledge",
                        "财经知识助手",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.SIMPLE,
                        0.91,
                        true,
                        "domain_agent_finance_knowledge",
                        Map.of(),
                        List.of(),
                        Map.of())),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, true));
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, routeService);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).isNotEmpty();
                    assertThat(stream.getFirst().type()).isEqualTo("run.started");
                    assertThat(stream).anySatisfy(event -> {
                        assertThat(event.type()).isEqualTo("runtime.progress");
                        assertThat(event.payload()).containsEntry("source", "intent-agent")
                                .containsEntry("sourceType", "intent-start")
                                .containsEntry("stage", "intent_calling");
                    });
                    assertThat(stream).anySatisfy(event -> {
                        assertThat(event.type()).isEqualTo("runtime.progress");
                        assertThat(event.payload()).containsEntry("source", "intent-agent")
                                .containsEntry("sourceType", "intent-result")
                                .containsEntry("routeAction", "ROUTE_SINGLE")
                                .containsEntry("targetProvider", "domain-agent");
                    });
                    int progressIndex = indexOfEvent(stream, "runtime.progress", "intent-start");
                    int completedIndex = indexOfEvent(stream, "run.completed", null);
                    assertThat(progressIndex).isGreaterThanOrEqualTo(0);
                    assertThat(completedIndex).isGreaterThan(progressIndex);
                })
                .verifyComplete();

        assertThat(events.events).extracting(ChatEvent::type)
                .containsSubsequence("run.started", "runtime.progress", "run.completed");
        assertThat(messages.parts).anySatisfy(part -> {
            assertThat(part.partType()).isEqualTo("METADATA");
            assertThat(part.payload()).containsEntry("metadataType", "selected_domain_agent")
                    .containsEntry("intentId", "domain_agent_finance_knowledge")
                    .containsEntry("intentName", "财经知识助手");
            assertThat(part.payload().get("intentResult")).isInstanceOfSatisfying(Map.class,
                    intentResult -> assertThat(intentResult)
                            .containsEntry("intentId", "domain_agent_finance_knowledge")
                            .containsEntry("intentName", "财经知识助手"));
        });
        ChatEvent completed = events.events.stream()
                .filter(event -> "run.completed".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(sessions.sessions.values()).singleElement()
                .satisfies(session -> {
                    assertThat(session.latestMessageSeq()).isEqualTo(completed.sequence());
                    assertThat(session.lastReadSeq()).isZero();
                    assertThat(session.hasUnread()).isTrue();
                });
    }

    @Test
    void intentFailureStrategyFailRunEndsWithoutCallingRuntimeOrSavingAssistant() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> {
                    throw new IllegalStateException("intent down");
                }),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, true, IntentFailureStrategy.FAIL_RUN));
        AtomicInteger runtimeCalls = new AtomicInteger();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type)
                            .containsSubsequence("run.started", "runtime.progress", "runtime.progress", "run.failed")
                            .doesNotContain("run.completed");
                    assertThat(stream.getLast().payload())
                            .containsEntry("code", "INTENT_ROUTING_FAILED")
                            .containsEntry("source", "intent-agent")
                            .containsEntry("failureStrategy", "FAIL_RUN")
                            .containsEntry("suggestedAction", "SELECT_DOMAIN_AGENT")
                            .containsEntry("retryable", true);
                })
                .verifyComplete();

        assertThat(runtimeCalls).hasValue(0);
        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
        assertThat(runs.runs.values()).singleElement()
                .extracting(ChatRun::status)
                .isEqualTo(ChatRunStatus.FAILED);
    }

    @Test
    void resolvedRouteUpdateFailureDoesNotStartRuntime() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        FailingResolvedRouteRunRepository runs = new FailingResolvedRouteRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.agentRuntime("relay"))));
            }
        };
        AtomicInteger runtimeCalls = new AtomicInteger();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeCalls.incrementAndGet();
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "unexpected"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .contains("run.started", "run.failed")
                        .doesNotContain("run.completed"))
                .verifyComplete();

        assertThat(runtimeCalls).hasValue(0);
    }

    @Test
    void attachmentOnlyNextUsesTrustedFileNameOnlyForIntentQuery() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicReference<ChatCommand> routedCommand = new AtomicReference<>();
        AtomicReference<String> intentQuery = new AtomicReference<>();
        AtomicReference<AgentRuntimeRequest> runtimeRequest = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routedCommand.set(request.command());
                intentQuery.set(request.intentQuery());
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "relay", "no_match", com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeRequest.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "文档回答"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, runtime,
                new CapturingRuntimeBindingRepository(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                liveEventBus());

        StepVerifier.create(service.executeRun(user, new TraceContext("relay-trace-attachment"), new ChatCommand(
                                "cmd-attachment-only", null, null, null, null, "web", null,
                                List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)),
                                Map.of(
                                        "language", "zh_CN",
                                        "sceneParam", Map.of(
                                                "region", "CN",
                                                "docList", List.of(Map.of("docId", "forged"))))),
                                RuntimeForwardHeaders.empty())
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .containsExactly("run.started", "message.snapshot", "run.completed"))
                .verifyComplete();

        assertThat(routedCommand.get()).isNotNull();
        assertThat(routedCommand.get().message()).isEmpty();
        assertThat(intentQuery).hasValue("[用户上传文档] invoice.pdf");
        assertThat(runtimeRequest.get()).isNotNull();
        assertThat(runtimeRequest.get().traceContext().traceId()).isEqualTo("relay-trace-attachment");
        assertThat(runtimeRequest.get().message()).isEmpty();
        assertThat(runtimeRequest.get().attachments()).extracting(AttachmentRef::name)
                .containsExactly("invoice.pdf");
        assertThat(runtimeRequest.get().metadata()).containsEntry("language", "zh_CN");
        assertThat(runtimeRequest.get().metadata().get("sceneParam")).isInstanceOfSatisfying(Map.class,
                sceneParam -> {
                    assertThat(sceneParam).containsEntry("region", "CN");
                    assertThat(sceneParam.get("docList")).isEqualTo(List.of(Map.of(
                            "providerLocatorType", "DOC_ID",
                            "docId", "provider-doc1",
                            "docName", "invoice.pdf",
                            "docSize", 128L)));
                });
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEmpty();
                    assertThat(messages.attachments.stream()
                            .filter(attachment -> message.id().equals(attachment.messageId())))
                            .singleElement()
                            .satisfies(attachment -> {
                                assertThat(attachment.documentId()).isEqualTo("doc1");
                                assertThat(attachment.name()).isEqualTo("invoice.pdf");
                            });
                });
    }

    @Test
    void firstIntentDomainAgentRouteBuildsTrustedProviderDocumentMetadata() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        var intent = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                "finance.document", "文档分析", com.huawei.it.ex.one.domain.intent.TaskComplexity.SIMPLE,
                0.95, true, "skill-document", Map.of("routeAction", "ROUTE_SINGLE"), List.of(), Map.of());
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.domainAgent("skill-document", "intent-agent", 0.95, "intent matched"),
                        intent, 1L, 0.85)));
            }
        };
        AtomicReference<DomainAgentRequest> captured = new AtomicReference<>();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                captured.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "图片分析结果"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, noopRuntime(),
                new CapturingRuntimeBindingRepository(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                                "cmd-intent-document", null, null, null, null, "web", "分析这张图片",
                                List.of(new AttachmentRef("doc1", "forged.png", "image/png", 1L)),
                                Map.of("sceneParam", Map.of("region", "CN"))))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .contains("run.started", "message.snapshot", "run.completed"))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().query()).isEqualTo("分析这张图片");
        assertThat(captured.get().metadata().get("sceneParam")).isInstanceOfSatisfying(Map.class,
                sceneParam -> {
                    assertThat(sceneParam).containsEntry("region", "CN");
                    assertThat(sceneParam.get("docList")).isEqualTo(List.of(Map.of(
                            "providerLocatorType", "DOC_ID",
                            "docId", "provider-doc1",
                            "docName", "invoice.pdf",
                            "docSize", 128L)));
                });
    }

    @Test
    void forceRerouteCancelsActiveDomainAgentBindingAndRunsRouteSignals() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        NeverCancelRunCache runCache = new NeverCancelRunCache();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
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
                executionRegistry
        );
        ChatSession session = new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "测试会话", "ACTIVE", "web", Instant.now(), Instant.now());
        sessions.save(session);
        bindings.save(new RuntimeBinding("binding-domain", user.tenantId(), user.ownerUserId(),
                session.id(), RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER, null,
                "session1", RuntimeBindingStatus.ACTIVE, "run-old", Instant.now().plus(Duration.ofDays(1)),
                Instant.now(), Instant.now(), Map.of("domainAgentId", "skill-old")));
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routeCalls.incrementAndGet();
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"))));
            }
        };
        FinanceEXChatService service = ChatFlowTestFixture.service(
                new SessionApplicationService(sessions, messages, ids, permissionChecker),
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                routeService,
                intentRecordService(),
                domainAgentExecutor(documentFacade(), limiter),
                new SystemResponseExecutor(),
                new AgentRuntimeExecutor(noopRuntime(), limiter),
                documentFacade(),
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

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        session.id(), null, "web", "重新路由", List.of(), Map.of(),
                        null, null, ChatRunMode.NEXT, null, null, null,
                        RouteMemoryApplicationService.TRIGGER_USER_CORRECTION)).collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type).contains("run.completed"))
                .verifyComplete();

        assertThat(routeCalls.get()).isEqualTo(1);
        assertThat(bindings.savedHistory).anySatisfy(binding -> {
            assertThat(binding.id()).isEqualTo("binding-domain");
            assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        });
    }

    @Test
    void recordsEffectiveRelayRouteBeforeRuntimeAndKeepsItWhenRuntimeFails() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        CapturingRouteMemoryService routeMemory = new CapturingRouteMemoryService();
        AtomicBoolean recordedBeforeRuntime = new AtomicBoolean();
        AtomicReference<AgentRuntimeRequest> runtimeRequest = new AtomicReference<>();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                runtimeRequest.set(request);
                recordedBeforeRuntime.set(routeMemory.routeDecisions.size() == 1);
                return Flux.error(new IllegalStateException("relay failed after route binding"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                var intent = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "multi_intent", "多意图", com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.7, false, null, Map.of(
                                "routeAction", "ROUTE_MULTI",
                                "candidateIntentNames", List.of("财经智能问数", "财经知识助手")),
                        List.of(), Map.of());
                RouteTarget route = RouteTarget.agentRuntime("intent-agent", 0.7, "multiple intents");
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(route, intent, 5L, 0.0)));
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, routeService, runtime,
                new InMemoryExecutionRepository(), new ChatRunOperationalProperties(),
                new NeverCancelRunCache(), routeMemory);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                        "cmd-route-failure", null, null, null, null, "web",
                        "需要处理一个复杂任务",
                        List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)),
                        Map.of())).collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .contains("run.started", "run.failed"))
                .verifyComplete();

        assertThat(recordedBeforeRuntime).isTrue();
        assertThat(runtimeRequest.get().message()).isEqualTo("需要处理一个复杂任务");
        assertThat(routeMemory.routeDecisions).singleElement().satisfies(command -> {
            assertThat(command.query())
                    .isEqualTo("需要处理一个复杂任务 [用户上传文档] invoice.pdf");
            assertThat(command.intent().intentCode()).isEqualTo("relay");
            assertThat(command.intent().intentName()).isEqualTo("no_match");
            assertThat(command.intent().slots())
                    .containsEntry("routeAction", "ROUTE_MULTI")
                    .containsEntry("candidateIntentNames", List.of("财经智能问数", "财经知识助手"));
        });
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEqualTo("需要处理一个复杂任务");
                    assertThat(messages.attachments).singleElement()
                            .satisfies(attachment -> assertThat(attachment.name()).isEqualTo("invoice.pdf"));
                });
        assertThat(runs.runs.values()).singleElement()
                .satisfies(run -> assertThat(run.status()).isEqualTo(ChatRunStatus.FAILED));
    }
}
