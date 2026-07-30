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
class ChatDomainAgentRefusalFlowTest extends ChatFlowTestSupport {
    @Test
    void domainAgentRefusalRerouteUsesProgressFramesAndDoesNotCallBlockingRouteInitial() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicBoolean blockingRouteInitialCalled = new AtomicBoolean(false);
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger oldAgentTailSubscriptions = new AtomicInteger();
        AtomicReference<Map<String, Object>> rerouteMetadata = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public RouteSignalResult routeInitial(UserContext routeUser, ChatSession session, ChatCommand command,
                                                  List<AttachmentRef> attachments,
                                                  com.huawei.it.ex.one.domain.memory.MemoryContext memory) {
                blockingRouteInitialCalled.set(true);
                throw new AssertionError("blocking routeInitial must not be used for DomainAgent reroute");
            }

            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    var initialIntent = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                            "intent-a", "财经知识问答",
                            com.huawei.it.ex.one.domain.intent.TaskComplexity.SIMPLE,
                            1.0, true, "agent-a", Map.of("routeAction", "ROUTE_SINGLE"), List.of(), Map.of());
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"),
                            initialIntent, 1L, 0.85)));
                }
                rerouteMetadata.set(request.command().metadata());
                assertThat(events.events).anySatisfy(event -> assertThat(event.payload())
                        .containsEntry("sourceType", "agent.refusal")
                        .containsEntry("code", "FN-EX-CAHT-BIZ-DAG-001"));
                return Flux.just(
                        RouteSignalFrame.event(RuntimeEvent.progress(request.runId(), request.session().id(), Map.of(
                                "source", "intent-agent",
                                "sourceType", "intent-start",
                                "stage", "intent_calling",
                                "message", "正在识别问题意图",
                                "routeTrigger", "domain_reject"))),
                        RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                                "agent-b", "intent-agent", 1.0, "rerouted after refusal"))));
            }
        };
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public String provider() {
                return "domain-agent";
            }

            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                if ("agent-a".equals(request.routeTarget().selectedAgentCode())) {
                    return Flux.concat(
                            Flux.just(
                                    MessageSnapshotEvent.of(request.runId(), request.sessionId(), "obsolete answer"),
                                    domainAgentRefusalEvent(request.runId(), request.sessionId())),
                            Flux.defer(() -> {
                                oldAgentTailSubscriptions.incrementAndGet();
                                return Flux.just(MessageDeltaEvent.of(
                                        request.runId(), request.sessionId(), "late old-agent output"));
                            }));
                }
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "rerouted answer"),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, routeService,
                runtime, false);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(blockingRouteInitialCalled.get()).isFalse();
                    assertThat(stream).anySatisfy(event -> assertThat(event.payload())
                            .containsEntry("source", "intent-agent")
                            .containsEntry("sourceType", "intent-start"));
                    assertThat(stream).extracting(ChatEvent::type)
                            .contains("message.delta", "run.completed")
                            .doesNotContain("run.failed");
                })
                .verifyComplete();

        assertThat(oldAgentTailSubscriptions).hasValue(0);
        assertThat(rerouteMetadata.get().get("lastIntentRejectReason")).isEqualTo(Map.of(
                "lastIntent", "财经知识问答",
                "domainRejectMessage", "cannot answer this domain"));
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.content()).isEqualTo("rerouted answer");
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("MESSAGE_SNAPSHOT", "DOMAIN_AGENT_REFUSAL", "ANSWER");
                });
    }

    @Test
    void legacyRefusalCodeWithoutControlTypeDoesNotTriggerReroute() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = countingRuntimeRouteService(routeCalls);
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public String provider() {
                return "domain-agent";
            }

            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(
                        RuntimeEvent.progress(request.runId(), request.sessionId(), Map.of(
                                "code", "DOMAIN_REJECT",
                                "reasonCode", "OUT_OF_DOMAIN")),
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "current agent answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(
                sessions, messages, runs, events, routeService, runtime, false);

        StepVerifier.create(service.executeRun(user, new ChatCommand(
                        null, null, null, null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null))
                        .collectList())
                .assertNext(stream -> assertThat(stream).extracting(ChatEvent::type)
                        .contains("runtime.progress", "message.delta", "run.completed")
                        .doesNotContain("run.waiting_user"))
                .verifyComplete();

        assertThat(routeCalls).hasValue(0);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::content)
                .isEqualTo("current agent answer");
    }

    @Test
    void domainAgentRefusalNoMatchExecutesRelayInSameRun() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicReference<Map<String, Object>> rerouteMetadata = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                rerouteMetadata.set(request.command().metadata());
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.no_intent", "未识别到可用意图",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.just(
                        domainAgentRefusalEvent(request.runId(), request.sessionId()),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AtomicReference<RuntimeSessionMode> relaySessionMode = new AtomicReference<>();
        AtomicReference<TraceContext> relayTraceContext = new AtomicReference<>();
        AtomicReference<Map<String, Object>> relayMetadata = new AtomicReference<>();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                relaySessionMode.set(request.runtimeSessionMode());
                relayTraceContext.set(request.traceContext());
                relayMetadata.set(request.metadata());
                return Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "relay answer"),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClient(
                sessions, messages, runs, events, routeService, domainClient, relay);

        StepVerifier.create(service.executeRun(user, new TraceContext("refusal-reroute-trace"),
                        new ChatCommand("cmd1", null, null, null, null, "web", "hello",
                                List.of(new AttachmentRef("doc1", "forged-name.txt", "text/plain", 1L)),
                                Map.of("sceneParam", Map.of(
                                        "region", "CN",
                                        "docList", List.of(Map.of("docId", "forged"))))),
                        RuntimeForwardHeaders.empty()).collectList())
                .assertNext(stream -> {
                    assertThat(stream).anySatisfy(event -> assertThat(event.payload())
                            .containsEntry("sourceType", "domain-agent-reroute")
                            .containsEntry("action", "ROUTE_TO_RELAY"));
                    assertThat(stream).extracting(ChatEvent::type)
                            .contains("message.delta", "run.completed")
                            .doesNotContain("run.failed");
                })
                .verifyComplete();

        assertThat(relayCalls).hasValue(1);
        assertThat(rerouteMetadata.get().get("lastIntentRejectReason")).isEqualTo(Map.of(
                "lastIntent", "未知意图",
                "domainRejectMessage", "cannot answer this domain"));
        assertThat(relaySessionMode).hasValue(RuntimeSessionMode.NEW);
        assertThat(relayTraceContext).hasValue(new TraceContext("refusal-reroute-trace"));
        assertThat(relayMetadata.get().get("sceneParam")).isInstanceOfSatisfying(Map.class,
                sceneParam -> {
                    assertThat(sceneParam).containsEntry("region", "CN");
                    assertThat(sceneParam.get("docList")).isEqualTo(List.of(Map.of(
                            "providerLocatorType", "DOC_ID",
                            "docId", "provider-doc1",
                            "docName", "invoice.pdf",
                            "docSize", 128L)));
                });
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::content)
                .isEqualTo("relay answer");
        assertThat(runs.runs.values()).singleElement()
                .satisfies(run -> {
                    assertThat(run.runtimeProvider()).isEqualTo("relay");
                    assertThat(run.agentCode()).isNull();
                });
    }

    @Test
    void userConfirmedRefusalAutoSwitchesToHistoricalRelaySessionWhenEnabled() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(),
                "test", "ACTIVE", "web", now, now));
        bindings.save(new RuntimeBinding("domain-binding", user.tenantId(), user.ownerUserId(), "session1",
                "domain-agent", "domain-leaf", "domain-session-a", RuntimeBindingStatus.ACTIVE, "old-domain-run",
                null, now.minus(Duration.ofDays(1)), now.minus(Duration.ofDays(1)),
                Map.of("domainAgentId", "agent-a", "routeSource", "user-confirmed",
                        "intentName", "财经知识问答")));
        bindings.save(new RuntimeBinding("relay-binding", user.tenantId(), user.ownerUserId(), "session1",
                "relay", "relay-leaf", "relay-session-1", RuntimeBindingStatus.RESUMABLE, "old-run",
                null, now.minus(Duration.ofDays(30)), now.minus(Duration.ofDays(30)),
                Map.of("runtimeSessionEstablished", true)));
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicReference<Map<String, Object>> rerouteMetadata = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routeCalls.incrementAndGet();
                rerouteMetadata.set(request.command().metadata());
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.no_intent", "未识别到可用意图",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        DomainAgentClient domainClient = refusingDomainAgentClient();
        AtomicReference<RuntimeSessionMode> relaySessionMode = new AtomicReference<>();
        AtomicReference<String> relayRuntimeSessionId = new AtomicReference<>();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relaySessionMode.set(request.runtimeSessionMode());
                relayRuntimeSessionId.set(request.runtimeSessionId());
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "relay answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        com.huawei.it.ex.one.application.config.DomainAgentProperties properties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        properties.setRefusalAutoSwitchEnabled(true);
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relay, bindings,
                properties);
        AtomicReference<ChatRunStartResult> started = new AtomicReference<>();

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        "cmd1", null, null, "session1", null, "web", "hello", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty()))
                .assertNext(started::set)
                .verifyComplete();

        awaitEvent(events, "run.completed");
        assertThat(routeCalls).hasValue(1);
        assertThat(rerouteMetadata.get().get("lastIntentRejectReason")).isEqualTo(Map.of(
                "lastIntent", "财经知识问答",
                "domainRejectMessage", "cannot answer this domain"));
        assertThat(events.events).extracting(ChatEvent::type).doesNotContain("run.waiting_user");
        assertThat(relaySessionMode).hasValue(RuntimeSessionMode.RESUME);
        assertThat(relayRuntimeSessionId).hasValue("relay-session-1");
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
                    assertThat(binding.metadata())
                            .containsEntry("routeSource", "user-confirmed")
                            .containsEntry("lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001");
                });
        assertThat(bindings.bindingsForProvider("relay"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.id()).isEqualTo("relay-binding");
                    assertThat(binding.runtimeSessionId()).isEqualTo("relay-session-1");
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
                });
        assertThat(runs.findById(started.get().runId()))
                .hasValueSatisfying(run -> {
                    assertThat(run.runtimeProvider()).isEqualTo("relay");
                    assertThat(run.agentCode()).isNull();
                    assertThat(run.runtimeSessionId()).isEqualTo("relay-session-1");
                });
    }

    @Test
    void automaticDomainAgentRefusalRepeatedCandidateUsesIntentResultUntilRerouteLimit() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = repeatedDomainAgentRouteService(routeCalls, "agent-a");
        AtomicInteger domainAgentCalls = new AtomicInteger();
        DomainAgentClient domainAgentClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                domainAgentCalls.incrementAndGet();
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainAgentClient, relay, bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());

        StepVerifier.create(service.startRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.completed");
        assertThat(routeCalls).hasValue(4);
        assertThat(domainAgentCalls).hasValue(4);
        assertThat(relayCalls).hasValue(0);
        assertThat(events.events).noneSatisfy(event -> assertThat(event.payload())
                .containsEntry("action", "NO_AVAILABLE_DOMAIN_AGENT"));
        assertThat(events.events).anySatisfy(event -> assertThat(event.payload())
                .containsEntry("action", "MAX_REROUTES_REACHED"));
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .hasSize(4)
                .allSatisfy(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
                    assertThat(binding.metadata())
                            .containsEntry("lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001");
                });
    }

    @Test
    void frontSelectedRefusalUsesSameIntentCandidateWithoutSwitchConfirmation() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = repeatedDomainAgentRouteService(routeCalls, "agent-a");
        AtomicInteger domainAgentCalls = new AtomicInteger();
        DomainAgentClient domainAgentClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if (domainAgentCalls.incrementAndGet() == 1) {
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "same agent answer"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                routeService,
                domainAgentClient,
                noopRuntime(),
                bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                liveEventBus(),
                interactions);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                                "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                                "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.completed");
        assertThat(routeCalls).hasValue(1);
        assertThat(domainAgentCalls).hasValue(2);
        assertThat(events.events).extracting(ChatEvent::type).doesNotContain("run.waiting_user", "run.failed");
        assertThat(interactions.requests).isEmpty();
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .extracting(ChatMessage::content)
                .isEqualTo("same agent answer");
    }

    @Test
    void ownerLossAfterReplacementBindingCreationCancelsUnstartedBinding() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        OwnerRejectingReplacementBindingRepository bindings =
                new OwnerRejectingReplacementBindingRepository(executions);
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger domainAgentCalls = new AtomicInteger();
        DomainAgentClient domainAgentClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if (domainAgentCalls.incrementAndGet() == 1) {
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "must not be called"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                repeatedDomainAgentRouteService(routeCalls, "agent-a"),
                domainAgentClient,
                noopRuntime(),
                bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(),
                liveEventBus(),
                new InMemoryInteractionRequestRepository(),
                runtimeBindingCache(),
                null,
                documentFacade(),
                executions);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                                "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                                null, null, ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitValue(bindings.replacementStatus, RuntimeBindingStatus.CANCELLED,
                "unstarted replacement binding cancellation");
        assertThat(routeCalls).hasValue(2);
        assertThat(domainAgentCalls).hasValue(1);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .hasSize(2)
                .allSatisfy(binding -> assertThat(binding.status())
                        .isEqualTo(RuntimeBindingStatus.CANCELLED));
    }

    @Test
    void guardedRerouteEventRejectionCancelsBindingBeforeRuntimeSubscription() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        RejectingAutoSwitchEventStore events = new RejectingAutoSwitchEventStore();
        TrackingReplacementBindingRepository bindings = new TrackingReplacementBindingRepository();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger domainAgentCalls = new AtomicInteger();
        DomainAgentClient domainAgentClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if (domainAgentCalls.incrementAndGet() == 1) {
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "must not be called"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                repeatedDomainAgentRouteService(routeCalls, "agent-a"),
                domainAgentClient,
                noopRuntime(),
                bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                                "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                                null, null, ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitValue(bindings.replacementStatus, RuntimeBindingStatus.CANCELLED,
                "rejected reroute event binding cancellation");
        assertThat(routeCalls).hasValue(2);
        assertThat(domainAgentCalls).hasValue(1);
    }

    @Test
    void retriesTransientReplacementBindingCleanupFailure() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        RejectingAutoSwitchEventStore events = new RejectingAutoSwitchEventStore();
        RetryOnceReplacementBindingRepository bindings = new RetryOnceReplacementBindingRepository();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger domainAgentCalls = new AtomicInteger();
        DomainAgentClient domainAgentClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if (domainAgentCalls.incrementAndGet() == 1) {
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "must not be called"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        com.huawei.it.ex.one.application.config.DomainAgentProperties properties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        properties.setBindingCompensationMaxAttempts(2);
        properties.setBindingCompensationRetryBackoff(Duration.ofMillis(1));
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                repeatedDomainAgentRouteService(routeCalls, "agent-a"),
                domainAgentClient,
                noopRuntime(),
                bindings,
                properties);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                                "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                                null, null, ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitValue(bindings.replacementStatus, RuntimeBindingStatus.CANCELLED,
                "retried replacement binding cancellation");
        assertThat(bindings.cancellationAttempts).hasValue(2);
        assertThat(domainAgentCalls).hasValue(1);
    }

    @Test
    void stopAfterReplacementRuntimeSubscriptionKeepsExistingBindingSemantics() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger domainAgentCalls = new AtomicInteger();
        AtomicReference<AgentRuntimeCancelRequest> cancelRequest = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                String domainAgentId = routeCalls.incrementAndGet() == 1 ? "agent-a" : "agent-b";
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                        domainAgentId, "intent-agent", 1.0, "test intent route"))));
            }
        };
        DomainAgentClient domainAgentClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if (domainAgentCalls.incrementAndGet() == 1) {
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                return Flux.never();
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AgentRuntime cancellationRuntime = new AgentRuntime() {
            @Override
            public String provider() {
                return "domain-agent";
            }

            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                cancelRequest.set(request);
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions,
                messages,
                runs,
                events,
                routeService,
                domainAgentClient,
                cancellationRuntime,
                bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicReference<ChatRunStartResult> started = new AtomicReference<>();

        StepVerifier.create(service.startRun(user, new ChatCommand(
                                "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                                null, null, ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(started::set)
                .verifyComplete();
        awaitAtomicValue(domainAgentCalls, 2, "replacement DomainAgent subscription");

        StepVerifier.create(service.stopRun(
                        user, started.get().runId(), RuntimeForwardHeaders.empty()))
                .expectNextCount(1)
                .verifyComplete();
        awaitEvent(events, "run.cancelled");

        assertThat(cancelRequest.get()).isNotNull();
        assertThat(cancelRequest.get().runtimeTargetId()).isEqualTo("agent-b");
        assertThat(runs.findById(started.get().runId()))
                .hasValueSatisfying(run -> {
                    assertThat(run.runtimeProvider()).isEqualTo("domain-agent");
                    assertThat(run.agentCode()).isEqualTo("agent-b");
                });
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .filteredOn(binding -> binding.status() == RuntimeBindingStatus.ACTIVE)
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.lastRunId()).isEqualTo(started.get().runId());
                    assertThat(binding.metadata()).containsEntry("domainAgentId", "agent-b");
                });
    }

    @Test
    void refusalAtRerouteLimitUsesConfiguredManualBindingLifecycle() {
        com.huawei.it.ex.one.application.config.DomainAgentProperties properties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        properties.setMaxReroutes(0);

        MultiBindingRuntimeBindingRepository automaticBindings = new MultiBindingRuntimeBindingRepository();
        InMemoryEventStore automaticEvents = new InMemoryEventStore();
        FinanceEXChatService automaticService = financeServiceWithDomainClientAndBindings(
                new InMemorySessionRepository(), new InMemoryMessageRepository(), new InMemoryRunRepository(),
                automaticEvents, repeatedDomainAgentRouteService(new AtomicInteger(), "agent-a"),
                refusingDomainAgentClient(), noopRuntime(), automaticBindings, properties);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(automaticService.startRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(automaticEvents, "run.completed");

        assertThat(automaticBindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .extracting(RuntimeBinding::status)
                .isEqualTo(RuntimeBindingStatus.CANCELLED);

        MultiBindingRuntimeBindingRepository manualBindings = new MultiBindingRuntimeBindingRepository();
        InMemoryEventStore manualEvents = new InMemoryEventStore();
        FinanceEXChatService manualService = financeServiceWithDomainClientAndBindings(
                new InMemorySessionRepository(), new InMemoryMessageRepository(), new InMemoryRunRepository(),
                manualEvents, repeatedDomainAgentRouteService(new AtomicInteger(), "unused"),
                refusingDomainAgentClient(), noopRuntime(), manualBindings, properties);

        StepVerifier.create(manualService.startRun(user, new ChatCommand(
                        "cmd2", null, null, null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(manualEvents, "run.completed");

        assertThat(manualBindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
                    assertThat(binding.metadata()).containsEntry("routeSource", "front-selected");
                });

        com.huawei.it.ex.one.application.config.DomainAgentProperties autoSwitchProperties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        autoSwitchProperties.setMaxReroutes(0);
        autoSwitchProperties.setRefusalAutoSwitchEnabled(true);
        MultiBindingRuntimeBindingRepository autoSwitchBindings = new MultiBindingRuntimeBindingRepository();
        InMemoryEventStore autoSwitchEvents = new InMemoryEventStore();
        FinanceEXChatService autoSwitchService = financeServiceWithDomainClientAndBindings(
                new InMemorySessionRepository(), new InMemoryMessageRepository(), new InMemoryRunRepository(),
                autoSwitchEvents, repeatedDomainAgentRouteService(new AtomicInteger(), "unused"),
                refusingDomainAgentClient(), noopRuntime(), autoSwitchBindings, autoSwitchProperties);

        StepVerifier.create(autoSwitchService.startRun(user, new ChatCommand(
                        "cmd3", null, null, null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(autoSwitchEvents, "run.completed");

        assertThat(autoSwitchBindings.bindingsForProvider("domain-agent"))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
                    assertThat(binding.metadata())
                            .containsEntry("routeSource", "front-selected")
                            .containsEntry("lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001");
                });
    }

    @Test
    void automaticRefusalCancelsBindingBeforePublishAndStopDoesNotReuseIt() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                int call = routeCalls.incrementAndGet();
                if (call == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                if (call == 2) {
                    return Flux.never();
                }
                return Flux.just(RouteSignalFrame.result(
                        RouteSignalResult.of(RouteTarget.agentRuntime("intent-agent", 1.0, "rerouted"))));
            }
        };
        AtomicInteger domainCalls = new AtomicInteger();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                domainCalls.incrementAndGet();
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "relay answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicReference<RuntimeBindingStatus> bindingStatusAtRefusalPublish = new AtomicReference<>();
        ChatLiveEventBus eventBus = new ChatLiveEventBus() {
            @Override
            public void publish(String topicId, ChatEvent event) {
                if (event != null && event.payload() != null
                        && "agent.refusal".equals(event.payload().get("sourceType"))
                        && "FN-EX-CAHT-BIZ-DAG-001".equals(event.payload().get("code"))) {
                    bindings.bindingsForProvider("domain-agent").stream()
                            .findFirst()
                            .map(RuntimeBinding::status)
                            .ifPresent(bindingStatusAtRefusalPublish::set);
                }
            }

            @Override
            public Flux<ChatEvent> subscribe(String topicId) {
                return Flux.never();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, relay, bindings,
                new com.huawei.it.ex.one.application.config.DomainAgentProperties(), eventBus);
        AtomicReference<ChatRunStartResult> firstRun = new AtomicReference<>();

        StepVerifier.create(service.startRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(firstRun::set)
                .verifyComplete();

        awaitValue(bindingStatusAtRefusalPublish, RuntimeBindingStatus.CANCELLED,
                "automatic refusal binding status at publish");
        StepVerifier.create(service.stopRun(user, firstRun.get().runId(), RuntimeForwardHeaders.empty()))
                .expectNextCount(1)
                .verifyComplete();
        awaitEvent(events, "run.cancelled");

        StepVerifier.create(service.startRun(user, new ChatCommand("cmd2", null, null,
                        firstRun.get().sessionId(), null, "web", "next question", List.of(), Map.of()),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(events, "run.completed");

        assertThat(routeCalls).hasValue(3);
        assertThat(domainCalls).hasValue(1);
        assertThat(relayCalls).hasValue(1);
    }

    @Test
    void automaticRefusalCommitAndCacheSyncDoNotBlockMainEventIo() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        ThreadCapturingRuntimeBindingRepository bindings = new ThreadCapturingRuntimeBindingRepository();
        BlockingRuntimeBindingCache bindingCache = new BlockingRuntimeBindingCache();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger relayCalls = new AtomicInteger();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                return Flux.just(RouteSignalFrame.result(
                        RouteSignalResult.of(RouteTarget.agentRuntime("intent-agent", 1.0, "relay fallback"))));
            }
        };
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.just(
                        MessageSnapshotEvent.of(request.runId(), request.sessionId(), "relay answer"),
                        com.huawei.it.ex.one.domain.chat.MessageCompletedEvent.of(
                                request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        reactor.core.scheduler.Scheduler controlScheduler = reactor.core.scheduler.Schedulers.newBoundedElastic(
                1, 16, "test-domain-agent-control-io");
        try {
            FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                    sessions, messages, runs, events, routeService, refusingDomainAgentClient(), relay, bindings,
                    new com.huawei.it.ex.one.application.config.DomainAgentProperties(), liveEventBus(),
                    new InMemoryInteractionRequestRepository(), bindingCache, controlScheduler);

            StepVerifier.create(service.startRun(user, new ChatCommand("cmd1", null, null,
                            null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty()))
                    .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                    .verifyComplete();

            assertThat(bindingCache.awaitEvictionStarted()).isTrue();
            awaitAtomicValue(routeCalls, 2, "refusal reroute decision");
            awaitAtomicValue(relayCalls, 1, "relay invocation");
            awaitEvent(events, "run.completed");
            assertThat(bindings.cancellationThread.get()).startsWith("test-domain-agent-control-io");
            assertThat(routeCalls).hasValue(2);
            assertThat(relayCalls).hasValue(1);
        } finally {
            bindingCache.releaseEviction();
            controlScheduler.dispose();
        }
    }

    @Test
    void refusalIntentClarificationAutoSwitchLoadsCancelledExpiredFrontSelectedBindingById() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", user.tenantId(), user.ownerUserId(), "测试会话", "ACTIVE", "web",
                "msg-user", "msg-assistant", null, null, 1L, null, now, now));
        messages.save(new ChatMessage("msg-user", user.tenantId(), user.ownerUserId(), "session1",
                null, 1L, 0, 1, "user", "原始问题", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("msg-assistant", user.tenantId(), user.ownerUserId(), "session1",
                "msg-user", 2L, 1, 1, "assistant", "请补充具体场景", null, "run-source",
                "NORMAL", false, null, null, null, null, null, now));
        AgentModeProfile rejectedMode = new AgentModeProfile(List.of(
                new AgentModeSelection("thinking", "deep", "深度思考")));
        bindings.save(new RuntimeBinding("binding-domain-a", user.tenantId(), user.ownerUserId(), "session1",
                "domain-agent", "msg-assistant", "domain-session-a", RuntimeBindingStatus.CANCELLED, "run-source",
                now.minus(Duration.ofMinutes(1)), now.minus(Duration.ofDays(1)), now,
                AgentModeBindingContext.apply(Map.of(
                        "domainAgentId", "agent-a",
                        "routeSource", "front-selected",
                        "lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001"), rejectedMode)));
        Map<String, Object> rerouteContext = Map.ofEntries(
                Map.entry("currentProvider", "domain-agent"),
                Map.entry("currentTargetId", "agent-a"),
                Map.entry("currentBindingId", "binding-domain-a"),
                Map.entry("currentRouteSource", "front-selected"),
                Map.entry("refusalCode", "FN-EX-CAHT-BIZ-DAG-001"),
                Map.entry("refusalReasonCode", "OUT_OF_DOMAIN"),
                Map.entry("refusalRecoverable", false),
                Map.entry("refusalReason", "当前请求不在该领域 Agent 处理范围内"),
                Map.entry("lastIntentRejectReason", Map.of(
                        "lastIntent", "领域 A",
                        "domainRejectMessage", "当前请求不在该领域 Agent 处理范围内")),
                Map.entry("rerouteCount", 0),
                Map.entry("rejectedDomainAgentIds", List.of("agent-a")),
                Map.entry("originalQuery", "原始问题"));
        ChatInteractionRequest waiting = new ChatInteractionRequest(
                "interaction1", user.tenantId(), user.ownerUserId(), "session1", "run-source", null,
                "msg-user", "msg-assistant", "intent-agent", null, null, null,
                ChatInteractionType.INTENT_CLARIFICATION, ChatInteractionStatus.WAITING,
                Map.of("source", "intent-agent",
                        "sourceType", "intent-clarification-request",
                        "interactionType", "INTENT_CLARIFICATION",
                        "originalQuery", "原始问题",
                        "clarifyQuestion", "请补充具体场景",
                        "domainAgentRerouteContext", rerouteContext),
                Map.of(), now.plus(Duration.ofHours(1)), null, null, now, now);
        interactions.insert(waiting);
        AtomicReference<Map<String, Object>> clarificationMetadata = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                clarificationMetadata.set(request.command().metadata());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                        "agent-b", "intent-agent", 1.0, "clarification resolved"))));
            }
        };
        AtomicInteger agentBCalls = new AtomicInteger();
        AtomicReference<Map<String, Object>> agentBMetadata = new AtomicReference<>();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if ("agent-b".equals(request.domainAgentId())) {
                    agentBCalls.incrementAndGet();
                    agentBMetadata.set(request.metadata());
                    return Flux.just(MessageSnapshotEvent.of(
                            request.runId(), request.sessionId(), "agent-b answer"));
                }
                return Flux.error(new AssertionError("refused agent must not be called again"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        com.huawei.it.ex.one.application.config.DomainAgentProperties properties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        properties.setRefusalAutoSwitchEnabled(true);
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, noopRuntime(), bindings,
                properties, liveEventBus(), interactions);

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, "session1", null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), null, null, Map.of("请补充具体场景", "账务审批")),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();
        awaitEvent(events, "run.completed");

        assertThat(events.events).extracting(ChatEvent::type).doesNotContain("run.failed");
        assertThat(events.events).extracting(ChatEvent::type).doesNotContain("run.waiting_user");
        assertThat(clarificationMetadata.get())
                .containsEntry("routeTrigger", "clarify_answer")
                .containsEntry("lastIntentRejectReason", Map.of(
                        "lastIntent", "领域 A",
                        "domainRejectMessage", "当前请求不在该领域 Agent 处理范围内"));
        assertThat(interactions.requests.values())
                .noneMatch(request -> request.interactionType() == ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        assertThat(agentBCalls).hasValue(1);
        assertThat(agentBMetadata.get())
                .doesNotContainKeys("domainAgentRerouteContext", "lastIntentRejectReason");
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .filteredOn(binding -> "agent-a".equals(binding.metadata().get("domainAgentId")))
                .singleElement()
                .extracting(RuntimeBinding::status)
                .isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .filteredOn(binding -> "agent-b".equals(binding.metadata().get("domainAgentId")))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
                    assertThat(AgentModeBindingContext.fromBinding(binding)).isNull();
                });
    }

    @Test
    void domainAgentRefusalIntentFailureFailRunDoesNotCallRelay() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (routeCalls.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-a", "intent-agent", 1.0, "initial intent route"))));
                }
                var failure = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.degraded", "意图服务不可用",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of(), List.of(), Map.of("reason", "intent down"));
                RouteSignalResult result = RouteSignalResult.intentFailure(
                        null, failure, 1L, 0.85,
                        new RouteSignalResult.IntentFailure(IntentFailureStrategy.FAIL_RUN, "intent down"));
                return Flux.just(
                        RouteSignalFrame.event(RuntimeEvent.progress(request.runId(), request.session().id(), Map.of(
                                "source", "intent-agent",
                                "sourceType", "intent-result",
                                "routeAction", "DEGRADED",
                                "failureStrategy", "FAIL_RUN",
                                "targetProvider", "none"))),
                        RouteSignalFrame.result(result));
            }
        };
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AgentRuntime relay = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClient(
                sessions, messages, runs, events, routeService, domainClient, relay);

        StepVerifier.create(service.executeRun(user, new ChatCommand("cmd1", null, null,
                        null, null, "web", "hello", List.of(), Map.of())).collectList())
                .assertNext(stream -> {
                    assertThat(stream).extracting(ChatEvent::type)
                            .contains("run.failed")
                            .doesNotContain("run.completed");
                    assertThat(stream.getLast().payload())
                            .containsEntry("code", "INTENT_ROUTING_FAILED")
                            .containsEntry("failureStrategy", "FAIL_RUN");
                })
                .verifyComplete();

        assertThat(relayCalls).hasValue(0);
        assertThat(messages.messages).extracting(ChatMessage::role).containsExactly("user");
    }

    @Test
    void frontSelectedRefusalAutoSwitchesToIntentDomainAgentWhenEnabled() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        AtomicInteger routeCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                routeCalls.incrementAndGet();
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                        "agent-b", "intent-agent", 1.0, "rerouted after refusal"))));
            }
        };
        AtomicInteger agentACalls = new AtomicInteger();
        AtomicInteger agentBCalls = new AtomicInteger();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if ("agent-a".equals(request.domainAgentId())) {
                    agentACalls.incrementAndGet();
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                agentBCalls.incrementAndGet();
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "agent-b answer"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicReference<RuntimeBindingStatus> bindingStatusAtRefusalPublish = new AtomicReference<>();
        ChatLiveEventBus eventBus = new ChatLiveEventBus() {
            @Override
            public void publish(String topicId, ChatEvent event) {
                if (event != null && event.payload() != null
                        && "agent.refusal".equals(event.payload().get("sourceType"))) {
                    bindings.bindingsForProvider("domain-agent").stream()
                            .filter(binding -> "agent-a".equals(binding.metadata().get("domainAgentId")))
                            .findFirst()
                            .map(RuntimeBinding::status)
                            .ifPresent(bindingStatusAtRefusalPublish::set);
                }
            }

            @Override
            public Flux<ChatEvent> subscribe(String topicId) {
                return Flux.never();
            }
        };
        com.huawei.it.ex.one.application.config.DomainAgentProperties properties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        properties.setRefusalAutoSwitchEnabled(true);
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, noopRuntime(), bindings,
                properties, eventBus, interactions);
        AtomicReference<ChatRunStartResult> started = new AtomicReference<>();

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(started::set)
                .verifyComplete();

        awaitEvent(events, "run.completed");
        awaitValue(bindingStatusAtRefusalPublish, RuntimeBindingStatus.CANCELLED,
                "front-selected refusal binding status at publish");
        assertThat(routeCalls).hasValue(1);
        assertThat(agentACalls).hasValue(1);
        assertThat(agentBCalls).hasValue(1);
        assertThat(events.events).extracting(ChatEvent::type).doesNotContain("run.waiting_user");
        assertThat(interactions.requests.values())
                .noneMatch(request -> request.interactionType() == ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .filteredOn(binding -> "agent-a".equals(binding.metadata().get("domainAgentId")))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
                    assertThat(binding.metadata())
                            .containsEntry("routeSource", "front-selected")
                            .containsEntry("lastRejectCode", "FN-EX-CAHT-BIZ-DAG-001");
                });
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .filteredOn(binding -> "agent-b".equals(binding.metadata().get("domainAgentId")))
                .singleElement()
                .satisfies(binding -> {
                    assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
                    assertThat(binding.metadata()).containsEntry("routeSource", "intent-agent");
                });
        assertThat(runs.findById(started.get().runId()))
                .hasValueSatisfying(run -> {
                    assertThat(run.runtimeProvider()).isEqualTo("domain-agent");
                    assertThat(run.agentCode()).isEqualTo("agent-b");
                });
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .satisfies(message -> assertThat(message.parts()).extracting(ChatMessagePart::partType)
                        .contains("DOMAIN_AGENT_REFUSAL", "MESSAGE_SNAPSHOT", "ANSWER")
                        .doesNotContain("ROUTE_SWITCH_CONFIRMATION_REQUEST",
                                "ROUTE_SWITCH_CONFIRMATION_RESPONSE"));
    }

    @Test
    void replacementResolvedRouteFailureDoesNotStartNewDomainAgent() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        FailingResolvedRouteRunRepository runs = new FailingResolvedRouteRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
        AtomicInteger agentACalls = new AtomicInteger();
        AtomicInteger agentBCalls = new AtomicInteger();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                        "agent-b", "intent-agent", 1.0, "rerouted after refusal"))));
            }
        };
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if ("agent-a".equals(request.domainAgentId())) {
                    agentACalls.incrementAndGet();
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                agentBCalls.incrementAndGet();
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "must not be called"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        com.huawei.it.ex.one.application.config.DomainAgentProperties properties =
                new com.huawei.it.ex.one.application.config.DomainAgentProperties();
        properties.setRefusalAutoSwitchEnabled(true);
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, routeService, domainClient, noopRuntime(), bindings,
                properties);
        UserContext user = new UserContext("tenant1", "user1", "User One");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                                "cmd1", null, null, null, null, "web", "hello", List.of(), Map.of(),
                                "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.failed");
        assertThat(agentACalls).hasValue(1);
        assertThat(agentBCalls).hasValue(0);
        assertThat(bindings.bindingsForProvider("domain-agent"))
                .allSatisfy(binding -> assertThat(binding.status())
                        .isEqualTo(RuntimeBindingStatus.CANCELLED));
        assertThat(events.events).extracting(ChatEvent::type)
                .contains("run.failed")
                .doesNotContain("run.completed");
    }

    @Test
    void frontSelectedRefusalRequiresConfirmationAndReusesAssistantForNewAgentAnswer() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        IdGenerator ids = new SequentialIdGenerator();
        PermissionChecker permissionChecker = new PermissionChecker();
        WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(
                new com.huawei.it.ex.one.application.config.ResourceIsolationProperties());
        LocalChatRunExecutionRegistry executionRegistry = new LocalChatRunExecutionRegistry();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids,
                permissionChecker);
        ChatStreamApplicationService streamService = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissionChecker, sessions,
                new com.huawei.it.ex.one.application.config.ChatWebSocketProperties());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events,
                permissionChecker, sessions);
        ChatRunLeaseApplicationService leaseService = new ChatRunLeaseApplicationService(
                executions, (ApplicationInstanceIdProvider) () -> "instance-test",
                new ChatRunOperationalProperties(), ids, executionRegistry);
        ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                interactions, ids, permissionChecker, new ChatInteractionProperties());
        ChatRunTerminalCommitService terminalCommitService = new ChatRunTerminalCommitService(
                streamService, sessionService, runs, leaseService, bindings, interactionService, Duration.ZERO);
        RuntimeBindingApplicationService bindingService = new RuntimeBindingApplicationService(
                bindings, runtimeBindingCache(), ids, Duration.ZERO, "relay");
        AtomicInteger rerouteDecisions = new AtomicInteger();
        AtomicReference<Map<String, Object>> rerouteMetadata = new AtomicReference<>();
        RouteSignalApplicationService routeService = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, routeUser) -> null),
                new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                rerouteMetadata.set(request.command().metadata());
                if (rerouteDecisions.incrementAndGet() == 1) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(RouteTarget.domainAgent(
                            "agent-b", "intent-agent", 1.0, "rerouted after refusal"))));
                }
                var noMatch = new com.huawei.it.ex.one.domain.intent.IntentDecision(
                        "finance.runtime.no_intent", "未识别到可用意图",
                        com.huawei.it.ex.one.domain.intent.TaskComplexity.COMPLEX,
                        0.0, false, null, Map.of("routeAction", "NO_MATCH"), List.of(), Map.of());
                return Flux.just(RouteSignalFrame.result(RouteSignalResult.ofIntent(
                        RouteTarget.agentRuntime("intent-agent", 0.0, "no match routes to relay"),
                        noMatch, 1L, 0.85)));
            }
        };
        AtomicInteger agentACalls = new AtomicInteger();
        AtomicInteger agentBCalls = new AtomicInteger();
        DomainAgentClient domainClient = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if ("agent-a".equals(request.domainAgentId())) {
                    agentACalls.incrementAndGet();
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                if (agentBCalls.incrementAndGet() == 1) {
                    return Flux.just(MessageSnapshotEvent.of(
                            request.runId(), request.sessionId(), "agent-b final answer"));
                }
                return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AtomicInteger relayCalls = new AtomicInteger();
        AgentRuntime relayRuntime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayCalls.incrementAndGet();
                return Flux.just(MessageSnapshotEvent.of(
                        request.runId(), request.sessionId(), "relay final answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        DocumentFacade documents = documentFacade();
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
                new RunAdmissionControlService(new com.huawei.it.ex.one.application.config.RunAdmissionProperties()),
                stopCoordinator,
                interactionService,
                terminalCommitService,
                ids,
                reactor.core.scheduler.Schedulers.boundedElastic(),
                new com.huawei.it.ex.one.application.config.DomainAgentProperties());

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, null, null, "web", "原问题", List.of(),
                        SelectedIntentContext.attach(Map.of("scene", "manual"), "intent-a", "领域 A"),
                        "DOMAIN_AGENT", "agent-a", ChatRunMode.NEXT, null, null, null),
                        RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.waiting_user");
        ChatInteractionRequest waiting = interactions.requests.values().stream()
                .filter(request -> request.status() == ChatInteractionStatus.WAITING)
                .findFirst()
                .orElseThrow();
        assertThat(waiting.interactionType()).isEqualTo(ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        assertThat(waiting.requestPayload())
                .containsEntry("currentTargetId", "agent-a")
                .containsEntry("candidateProvider", "domain-agent")
                .containsEntry("candidateTargetId", "agent-b")
                .containsEntry("refusalCode", "FN-EX-CAHT-BIZ-DAG-001");
        ChatMessage waitingAssistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role()))
                .findFirst()
                .orElseThrow();
        assertThat(waitingAssistant.parts()).extracting(ChatMessagePart::partType)
                .contains("DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST")
                .doesNotContain("ANSWER");
        assertThat(waitingAssistant.parts()).filteredOn(part -> "DOMAIN_AGENT_REFUSAL".equals(part.partType()))
                .singleElement()
                .satisfies(part -> assertThat(part.payload())
                        .containsEntry("domainAgentId", "agent-a")
                        .containsEntry("code", "FN-EX-CAHT-BIZ-DAG-001"));
        assertThat(agentACalls).hasValue(1);
        assertThat(agentBCalls).hasValue(0);
        assertThat(rerouteMetadata.get())
                .containsEntry("scene", "manual")
                .containsEntry("routeTrigger", "domain_reject")
                .containsKey("lastIntentRejectReason");
        assertThat(rerouteMetadata.get().get("lastIntentRejectReason")).isEqualTo(Map.of(
                "lastIntent", "领域 A",
                "domainRejectMessage", "cannot answer this domain"));
        assertThat(SelectedIntentContext.intentId(rerouteMetadata.get())).isNull();
        assertThat(SelectedIntentContext.intentName(rerouteMetadata.get())).isNull();

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, waiting.sessionId(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, waiting.id(), true, null, Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEvent(events, "run.completed");
        assertThat(agentBCalls).hasValue(1);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.id()).isEqualTo(waitingAssistant.id());
                    assertThat(message.content()).isEqualTo("agent-b final answer");
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST",
                                    "ROUTE_SWITCH_CONFIRMATION_RESPONSE", "MESSAGE_SNAPSHOT", "ANSWER");
                    assertThat(message.parts()).filteredOn(part -> "ANSWER".equals(part.partType())).hasSize(1);
                });
        assertThat(bindings.saved.metadata())
                .containsEntry("domainAgentId", "agent-b")
                .containsEntry("routeSource", "user-confirmed");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, waiting.sessionId(), null, "web", "第二个问题", List.of(), Map.of(),
                        null, null, ChatRunMode.NEXT, null, null, null), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        ChatInteractionRequest relayWaiting = awaitWaitingInteraction(interactions, waiting.id());
        assertThat(relayWaiting.interactionType()).isEqualTo(ChatInteractionType.ROUTE_SWITCH_CONFIRMATION);
        assertThat(relayWaiting.requestPayload())
                .containsEntry("currentRouteSource", "user-confirmed")
                .containsEntry("candidateProvider", "relay")
                .containsEntry("candidateTargetId", "relay");
        assertThat(relayCalls).hasValue(0);

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, relayWaiting.sessionId(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, relayWaiting.id(), false, null, Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEventCount(events, "run.completed", 2);
        assertThat(relayCalls).hasValue(0);
        assertThat(bindings.saved.metadata())
                .containsEntry("domainAgentId", "agent-b")
                .containsEntry("routeSource", "user-confirmed");
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .filteredOn(message -> "已保留当前领域 Agent，本轮不切换处理能力。".equals(message.content()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("ROUTE_SWITCH_CONFIRMATION_RESPONSE", "ROUTE_SWITCH_DECLINED", "ANSWER");
                    assertThat(message.parts()).filteredOn(part -> "ANSWER".equals(part.partType())).hasSize(1);
                });

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, waiting.sessionId(), null, "web", "第三个问题", List.of(), Map.of(),
                        null, null, ChatRunMode.NEXT, null, null, null), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        ChatInteractionRequest nextRelayWaiting = awaitWaitingInteraction(
                interactions, waiting.id(), relayWaiting.id());
        assertThat(nextRelayWaiting.requestPayload())
                .containsEntry("currentRouteSource", "user-confirmed")
                .containsEntry("candidateProvider", "relay");

        StepVerifier.create(service.startRun(user, new ChatCommand(
                        null, null, null, nextRelayWaiting.sessionId(), null, "web", null, List.of(), Map.of(),
                        null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                        null, nextRelayWaiting.id(), true, null, Map.of()), RuntimeForwardHeaders.empty()))
                .assertNext(result -> assertThat(result.firstSeq()).isGreaterThan(0L))
                .verifyComplete();

        awaitEventCount(events, "run.completed", 3);
        assertThat(relayCalls).hasValue(1);
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role()))
                .filteredOn(message -> "relay final answer".equals(message.content()))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.parts()).extracting(ChatMessagePart::partType)
                            .contains("DOMAIN_AGENT_REFUSAL", "ROUTE_SWITCH_CONFIRMATION_REQUEST",
                                    "ROUTE_SWITCH_CONFIRMATION_RESPONSE", "MESSAGE_SNAPSHOT", "ANSWER");
                    assertThat(message.parts()).filteredOn(part -> "ANSWER".equals(part.partType())).hasSize(1);
                });
    }
}
