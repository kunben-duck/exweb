/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import com.huawei.it.ex.one.application.config.ChatInteractionProperties;
import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.config.MemoryProperties;
import com.huawei.it.ex.one.application.config.ResourceIsolationProperties;
import com.huawei.it.ex.one.application.config.RunAdmissionProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeRegistry;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentChatRequestMapper;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentRuntime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

class DomainAgentQuestionnaireFlowTest extends ChatFlowTestSupport {
    private final UserContext user = new UserContext("tenant1", "user1", "user1");
    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final AtomicReference<Consumer<ChatEvent>> beforeAnswerAppend = new AtomicReference<>(event -> { });
    private final InMemoryEventStore events = new InMemoryEventStore() {
        @Override
        public ChatEvent append(ChatEvent event) {
            if ("clarification-response".equals(event.payload().get("sourceType"))) {
                beforeAnswerAppend.get().accept(event);
            }
            return super.append(event);
        }

        @Override
        public List<ChatEvent> sequenceLiveBatchWithExecutionGuard(List<ChatEvent> batch, RunExecutionClaim claim) {
            return batch.stream().map(event -> (ChatEvent) new StoredChatEvent(event.runId(), event.sessionId(),
                    ++seq, event.type(), Instant.now(), event.payload())).toList();
        }
    };
    private final InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
    private final InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
    private final CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(new ObjectMapper());
    private final AtomicInteger queries = new AtomicInteger();
    private final AtomicInteger routeCalls = new AtomicInteger();
    private final AtomicReference<AgentRuntimeInteractionResponseRequest> submitted = new AtomicReference<>();
    private final AtomicReference<RouteSignalRequest> rerouteRequest = new AtomicReference<>();
    private final AtomicReference<RouteSignalResult> rerouteResult = new AtomicReference<>(
            RouteSignalResult.of(RouteTarget.domainAgent("skill-a", "intent-agent", 1.0, "test")));
    private final AtomicReference<DomainAgentRequest> query = new AtomicReference<>();
    private final AtomicReference<AgentRuntimeRequest> relayQuery = new AtomicReference<>();
    private Function<AgentRuntimeInteractionResponseRequest, Flux<ChatEvent>> answer;
    private String initialFrames;
    private FinanceEXChatService service;

    @BeforeEach
    void setUp() {
        initialFrames = "data: {\"content\":\"before\"}\n\n" + prompt("q1")
                + "data: {\"content\":\"discarded\"}\n\n";
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(),
                    "data: {\"content\":\"after\"}\n\ndata: {\"endFlag\":true}\n\n"));
        });
        DomainAgentClient client = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                query.set(request);
                if (queries.incrementAndGet() > 1) {
                    return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(),
                            "data: {\"content\":\"rerouted\"}\n\ndata: {\"endFlag\":true}\n\n"));
                }
                return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(), initialFrames));
            }

            @Override
            public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
                submitted.set(request);
                return answer.apply(request);
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        var ids = new SequentialIdGenerator();
        var permissions = new PermissionChecker();
        var registry = new LocalChatRunExecutionRegistry();
        var sessionService = new SessionApplicationService(sessions, messages, ids, permissions);
        var streamService = new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(),
                liveEventBus(), runs, permissions, sessions, new ChatWebSocketProperties());
        var runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(), events, permissions, sessions);
        var leaseService = new ChatRunLeaseApplicationService(executions, () -> "test-instance",
                new ChatRunOperationalProperties(), ids, registry);
        var interactionService = new ChatInteractionApplicationService(interactions, ids, permissions,
                new ChatInteractionProperties());
        var runtime = new DomainAgentRuntime(client);
        AgentRuntime relay = new AgentRuntime() {
            @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                relayQuery.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "relay answer"));
            }
            @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
        };
        ResourceIsolationProperties limits = new ResourceIsolationProperties();
        limits.setDomainAgentMaxConcurrent(1);
        var executor = new AgentRuntimeExecutor(new AgentRuntimeRegistry(List.of(runtime, relay), "relay"),
                new WorkloadConcurrencyLimiter(limits));
        var routes = spy(runtimeRouteService());
        doAnswer(invocation -> {
            routeCalls.incrementAndGet();
            rerouteRequest.set(invocation.getArgument(0));
            return Flux.just(RouteSignalFrame.result(rerouteResult.get()));
        }).when(routes).routeInitialWithProgress(any());
        var terminal = new ChatRunTerminalCommitService(streamService, sessionService, runs, leaseService,
                bindings, interactionService, Duration.ZERO);
        var bindingService = new RuntimeBindingApplicationService(
                bindings, runtimeBindingCache(), ids, Duration.ZERO, "relay");
        service = ChatFlowTestFixture.service(sessionService,
                new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                bindingService,
                routes, intentRecordService(), new SystemResponseExecutor(), executor, documentFacade(),
                streamService, runService, leaseService, new ChatDeltaCoalescer(new ChatStreamProperties()), registry,
                new RunAdmissionControlService(new RunAdmissionProperties()),
                new ChatRunStopCoordinator(sessionService, streamService, runService, leaseService, registry, executor, ids),
                interactionService, terminal, ids, Schedulers.boundedElastic(), new DomainAgentProperties(), null);
        service.setRunAdmissionCommitService(new ChatRunAdmissionCommitService(
                sessionService, runService, interactionService, bindingService));
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void questionnaireSessionUpdatesWaitingStateAndAnswerRequest(boolean batchEnabled, boolean priorSession)
            throws Exception {
        configureBatching(batchEnabled);
        initialFrames = (priorSession ? "data: {\"sessionId\":\"domain-session-old\"}\n\n" : "")
                + promptWithSession("q1", "domain-session-new");
        ChatInteractionRequest waiting = start();
        assertWaitingSession(waiting, "domain-session-new");
        assertThat(waiting.sessionId()).isNotEqualTo("domain-session-new");
        List<ChatEvent> stored = events.events.stream()
                .filter(event -> waiting.sourceRunId().equals(event.runId())).toList();
        ChatEvent metadata = stored.stream().filter(event ->
                "domain-session-new".equals(event.payload().get("runtimeSessionId"))).findFirst().orElseThrow();
        ChatEvent card = stored.stream().filter(event ->
                "approval-request".equals(event.payload().get("sourceType"))).findFirst().orElseThrow();
        assertThat(metadata.sequence()).isLessThan(card.sequence());
        assertThat(card.sequence()).isLessThan(stored.getLast().sequence());
        assertThat(stored).allSatisfy(event -> assertThat(event.sessionId()).isEqualTo(waiting.sessionId()));
        assertThat(respond(waiting, true, Map.of("label", Map.of("期间", "本月"))).getLast().type())
                .isEqualTo("run.completed");
        assertAnswerSession(waiting, "domain-session-new");
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void consecutiveQuestionnaireRefreshesSessionInFullAndNoStore(boolean batchEnabled, boolean noStore)
            throws Exception {
        configureBatching(batchEnabled);
        initialFrames = promptWithSession("q1", "domain-session-first");
        ChatInteractionRequest first = start();
        assertWaitingSession(first, "domain-session-first");
        if (noStore) {
            var policy = new AgentDataPersistenceState("hidden")
                    .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER).markRuntimeDispatchStarted();
            runs.save(runs.runs.get(first.sourceRunId()).withMetadata(policy.runMetadataOverlay()));
        }
        String secondFrame = promptWithSession("q2", "domain-session-second");
        Function<AgentRuntimeInteractionResponseRequest, Flux<ChatEvent>> complete = answer;
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(), secondFrame));
        });
        List<ChatEvent> stored = respond(first, false, Map.of("ignore", true));
        assertThat(stored.getLast().type()).isEqualTo("run.waiting_user");
        assertAnswerSession(first, "domain-session-first");
        ChatInteractionRequest second = interactions.requests.values().stream()
                .filter(request -> "q2".equals(request.approvalId())).findFirst().orElseThrow();
        assertWaitingSession(second, "domain-session-second");
        assertThat(second.sessionId()).isEqualTo(first.sessionId());
        assertThat(second.runtimeBindingId()).isEqualTo(first.runtimeBindingId());
        assertThat(stored.stream().anyMatch(event ->
                "domain_agent_session".equals(event.payload().get("sourceType")))).isEqualTo(!noStore);
        answer = complete;
        assertThat(respond(second, true, Map.of("label", Map.of("期间", "本月"))).getLast().type())
                .isEqualTo("run.completed");
        assertAnswerSession(second, "domain-session-second");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void nullOrBlankQuestionnaireSessionKeepsExistingSession(String sessionId) throws Exception {
        initialFrames = "data: {\"sessionId\":\"domain-session-existing\"}\n\n"
                + promptWithSession("q1", sessionId);
        ChatInteractionRequest waiting = start();
        assertWaitingSession(waiting, "domain-session-existing");
        assertThat(respond(waiting, false, Map.of("ignore", true)).getLast().type()).isEqualTo("run.completed");
        assertAnswerSession(waiting, "domain-session-existing");
    }

    @Test
    void missingQuestionnaireSessionKeepsExistingSession() {
        initialFrames = "data: {\"sessionId\":\"domain-session-existing\"}\n\n" + prompt("q1");
        ChatInteractionRequest waiting = start();
        assertWaitingSession(waiting, "domain-session-existing");
        assertThat(respond(waiting, false, Map.of("ignore", true)).getLast().type()).isEqualTo("run.completed");
        assertAnswerSession(waiting, "domain-session-existing");
    }

    private void configureBatching(boolean enabled) {
        ChatStreamProperties properties = new ChatStreamProperties();
        properties.setEventBatchEnabled(enabled);
        service.setChatEventBatcher(new ChatEventBatcher(properties, new ObjectMapper()));
    }

    private void assertWaitingSession(ChatInteractionRequest waiting, String runtimeSessionId) {
        assertThat(waiting.runtimeSessionId()).isEqualTo(runtimeSessionId);
        assertThat(runs.runs.get(waiting.sourceRunId()).runtimeSessionId()).isEqualTo(runtimeSessionId);
        assertThat(runs.runs.get(waiting.sourceRunId()).status()).isEqualTo(ChatRunStatus.WAITING_USER);
        assertThat(bindings.findById(waiting.runtimeBindingId()).orElseThrow().runtimeSessionId())
                .isEqualTo(runtimeSessionId);
        assertThat(bindings.findById(waiting.runtimeBindingId()).orElseThrow().status())
                .isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(events.events).filteredOn(event -> waiting.sourceRunId().equals(event.runId())
                        && "run.waiting_user".equals(event.type()))
                .singleElement().satisfies(event -> assertThat(event.payload())
                        .containsEntry("runtimeSessionId", runtimeSessionId));
    }

    private void assertAnswerSession(ChatInteractionRequest waiting, String runtimeSessionId) {
        assertThat(submitted.get().sessionId()).isEqualTo(waiting.sessionId());
        assertThat(submitted.get().runtimeSessionId()).isEqualTo(runtimeSessionId);
        assertThat(new DomainAgentChatRequestMapper(new DomainAgentProperties()).toInteractionWireRequest(submitted.get()))
                .containsEntry("sessionId", runtimeSessionId).containsEntry("request_id", waiting.approvalId());
    }

    private String promptWithSession(String id, String sessionId) throws Exception {
        ObjectNode frame = (ObjectNode) new ObjectMapper().readTree(prompt(id).substring("data: ".length()));
        frame.put("sessionId", sessionId);
        return "data: " + frame + "\n\n";
    }

    @Test
    void anotherInstanceCanResumePersistedQuestionnaireWithoutPrivateContext() {
        ChatInteractionRequest waiting = start();
        ChatStreamApplicationService otherInstance = new ChatStreamApplicationService(events,
                new LocalChatEventStreamRegistry(), liveEventBus(), runs, new PermissionChecker(),
                sessions, new ChatWebSocketProperties());
        List<ChatEvent> replay = otherInstance.resumeRun(user, waiting.sourceRunId(), 0)
                .collectList().block(Duration.ofSeconds(5));
        assertThat(replay).isNotEmpty();
        assertThat(replay.getLast().type()).isEqualTo("run.waiting_user");
        assertThat(replay.getLast().payload()).containsEntry("interactionId", waiting.id());
        assertThat(replay).filteredOn(event -> "approval-request".equals(event.payload().get("sourceType")))
                .singleElement().satisfies(event -> assertThat(event.type()).isEqualTo("runtime.card"));
        assertThat(replay.toString()).doesNotContain(DomainAgentQuestionnaireContext.KEY, "private-value");
    }

    @Test
    void intentExpertQuestionnaireKeepsFrozenScopeThroughAnswerAndRefusal() {
        IntentExpertScope scope = new IntentExpertScope("expert-tax", "税务专家", "tax-entry");
        ChatInteractionRequest waiting = start(new ChatCommand(null, null, null, null, null,
                "web", "original question", List.of(), Map.of(), "INTENT_EXPERT", "expert-tax",
                ChatRunMode.NEXT, null, null, null).withIntentExpertScope(scope));
        assertThat(routeCalls).hasValue(1);
        assertThat(IntentExpertContext.fromMetadata(bindings.saved.metadata())).contains(scope);
        assertThat(DomainAgentQuestionnaireContext.command(waiting, Map.of(), "next").intentExpertScope())
                .isEqualTo(scope);
        refuseAnswer();
        rerouteResult.set(RouteSignalResult.of(RouteTarget.domainAgent("skill-b", "intent-agent", 1.0, "reroute")));
        List<ChatEvent> result = respond(waiting, true, Map.of("label", Map.of("期间", "本月")));
        assertThat(result.getLast().type()).isEqualTo("run.completed");
        assertThat(routeCalls).hasValue(2);
        assertThat(rerouteRequest.get().command().intentExpertScope()).isEqualTo(scope);
        assertThat(rerouteRequest.get().command().intentAccessName()).isEqualTo("tax-entry");
        assertThat(query.get().domainAgentId()).isEqualTo("skill-b");
        assertThat(IntentExpertContext.fromMetadata(bindings.saved.metadata())).contains(scope);
        assertThat(IntentExpertContext.fromMetadata(runs.runs.get(result.getFirst().runId()).metadata()))
                .contains(scope);
        assertThat(events.events.toString()).doesNotContain(DomainAgentQuestionnaireContext.KEY);
    }

    @Test
    void intentSelectedQuestionnaireCanRerouteToPersistentRelayExpert() {
        ChatInteractionRequest waiting = start(new ChatCommand(null, null, null, null, null,
                "web", "original question", List.of(), Map.of()));
        refuseAnswer();
        rerouteResult.set(RouteSignalResult.of(RouteTarget.domainExpertRuntime(
                "intent-agent", 1.0, "expert matched", "financial-analysis", "RE_financial-analysis")));
        List<ChatEvent> result = respond(waiting, true, Map.of("label", Map.of("期间", "本月")));
        assertThat(result.getLast().type()).isEqualTo("run.completed");
        assertThat(queries).hasValue(1);
        assertThat(routeCalls).hasValue(2);
        assertThat(relayQuery.get().routeTarget().runtimeRoleName()).isEqualTo("financial-analysis");
        assertThat(bindings.saved.provider()).isEqualTo("relay");
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(bindings.saved.metadata()).containsEntry("relayRoleName", "financial-analysis")
                .doesNotContainKey("relayExpertPinned");
    }

    @Test
    void answerKeepsThinkingSegmentsWithoutDuplicatingOldParts() {
        ChatInteractionRequest waiting = start();
        int previousParts = messages.messages.stream().filter(message -> message.id().equals(waiting.assistantMessageId()))
                .findFirst().orElseThrow().parts().size();
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(),
                    "data: {\"content\":\"<think>continue thinking</think>after\"}\n\n"
                            + "data: {\"endFlag\":true}\n\n"));
        });
        List<ChatEvent> result = respond(waiting, true, Map.of("label", Map.of("期间", "本月")));
        assertThat(result.getLast().type()).isEqualTo("run.completed");
        assertThat(messages.messages).filteredOn(message -> waiting.assistantMessageId().equals(message.id()))
                .singleElement().satisfies(message -> {
                    assertThat(message.content()).isEqualTo("before<!--DOMAIN_AGENT_CONTENT_SEGMENT-->after");
                    assertThat(message.parts()).filteredOn(part -> waiting.sourceRunId().equals(part.runId()))
                            .hasSize(previousParts);
                });
        assertThat(result).filteredOn(event -> "message.delta".equals(event.type()))
                .allSatisfy(event -> assertThat(event.payload().toString()).doesNotContain("CONTENT_SEGMENT", "before"));
    }

    @Test
    void answerSnapshotStillOverridesSeededBody() {
        ChatInteractionRequest waiting = start();
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "snapshot"));
        });
        assertThat(respond(waiting, false, Map.of("ignore", true)).getLast().type()).isEqualTo("run.completed");
        assertThat(messages.messages).filteredOn(message -> waiting.assistantMessageId().equals(message.id()))
                .singleElement().satisfies(message -> assertThat(message.content()).isEqualTo("snapshot"));
    }

    @Test
    void waitThenAnswerReusesAssistantAndNeverLeaksPrivateContext() {
        ChatInteractionRequest waiting = start();
        String originalAssistant = waiting.assistantMessageId();
        assertThat(waiting.runtimeProvider()).isEqualTo("domain-agent");
        assertThat(waiting.requestPayload()).containsKey(DomainAgentQuestionnaireContext.KEY);
        assertThat(runs.runs.get(waiting.sourceRunId()).status()).isEqualTo(ChatRunStatus.WAITING_USER);
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        List<ChatEvent> result = respond(waiting, true, Map.of("label", Map.of("期间", "自定义文本")));
        assertThat(result.getLast().type()).isEqualTo("run.completed");
        assertThat(queries).hasValue(1);
        assertThat(submitted.get().approvalId()).isEqualTo("q1");
        assertThat(submitted.get().runtimeMetadata()).containsEntry("userMessageId", waiting.userMessageId())
                .containsEntry("skillId", "skill-a");
        assertThat(result.getFirst().payload()).containsEntry("userMessageId", waiting.userMessageId());
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.ANSWERED);
        assertThat(messages.messages).filteredOn(message -> "user".equals(message.role())).hasSize(1);
        ChatMessage assistant = messages.messages.stream().filter(message -> "assistant".equals(message.role()))
                .findFirst().orElseThrow();
        assertThat(assistant.id()).isEqualTo(originalAssistant);
        assertThat(assistant.content()).isEqualTo("beforeafter");
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType)
                .contains("AGENT_CLARIFICATION_REQUEST", "AGENT_CLARIFICATION_RESPONSE");
        assertThat(events.events.toString()).doesNotContain(DomainAgentQuestionnaireContext.KEY, "private-value", "discarded");
        assertThat(assistant.parts().toString()).doesNotContain(DomainAgentQuestionnaireContext.KEY, "private-value");
        assertThat(bindings.saved.lastRunId()).isEqualTo(result.getFirst().runId());
    }

    @Test
    void ignoreCanLeadToAnotherQuestionnaireWithNewId() {
        ChatInteractionRequest first = start();
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(), prompt("q2")));
        });
        List<ChatEvent> result = respond(first, false, Map.of("ignore", true));
        assertThat(result.getLast().type()).isEqualTo("run.waiting_user");
        ChatInteractionRequest second = interactions.requests.values().stream()
                .filter(request -> "q2".equals(request.approvalId())).findFirst().orElseThrow();
        assertThat(second.assistantMessageId()).isEqualTo(first.assistantMessageId());
        assertThat(second.userMessageId()).isEqualTo(first.userMessageId());
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.requestPayload()).containsKey(DomainAgentQuestionnaireContext.KEY);
        assertThat(submitted.get().responsePayload()).containsEntry("approved", false);
    }

    @Test
    void invalidAnswerFailsBeforeClaimAndDoesNotSendControl() {
        ChatInteractionRequest waiting = start();
        assertThatThrownBy(() -> respond(waiting, true, Map.of("label", Map.of("unknown", "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(submitted.get()).isNull();
        assertThat(runs.runs).hasSize(1);
    }

    @Test
    void multipleChoiceAndCustomTextPreserveAnswerStructure() {
        ChatInteractionRequest waiting = start();
        Map<String, Object> labels = Map.of("期间", "自定义期间", "范围", List.of("境内", "境外"));
        assertThat(respond(waiting, true, Map.of("label", labels)).getLast().type()).isEqualTo("run.completed");
        assertThat(submitted.get().responsePayload()).containsEntry("questionnaireAnswers", Map.of("label", labels));
    }

    @Test
    void duplicateCompletedAnswerDoesNotCreateAnotherRun() {
        ChatInteractionRequest waiting = start();
        respond(waiting, false, Map.of("ignore", true));
        int runCount = runs.runs.size();
        AgentRuntimeInteractionResponseRequest first = submitted.get();
        assertThatThrownBy(() -> respond(waiting, false, Map.of("ignore", true)))
                .isInstanceOf(RuntimeException.class);
        assertThat(runs.runs).hasSize(runCount);
        assertThat(submitted.get()).isSameAs(first);
    }

    @Test
    void expiredQuestionnaireCannotSendAnswerOrCreateRun() {
        ChatInteractionRequest waiting = start();
        interactions.requests.put(waiting.id(), new ChatInteractionRequest(waiting.id(), waiting.tenantId(), waiting.userId(),
                waiting.sessionId(), waiting.sourceRunId(), waiting.continueRunId(), waiting.userMessageId(),
                waiting.assistantMessageId(), waiting.runtimeProvider(), waiting.runtimeBindingId(),
                waiting.runtimeSessionId(), waiting.approvalId(), waiting.interactionType(), waiting.status(),
                waiting.requestPayload(), waiting.responsePayload(), Instant.EPOCH, waiting.answeredAt(),
                waiting.cancelledAt(), waiting.createdAt(), waiting.updatedAt()));
        assertThatThrownBy(() -> respond(waiting, false, Map.of("ignore", true)))
                .isInstanceOf(ChatInteractionUnavailableException.class);
        assertThat(runs.runs).hasSize(1);
        assertThat(submitted.get()).isNull();
    }

    @Test
    void continuationHonorsPersistedNoStorePolicy() {
        ChatInteractionRequest waiting = start();
        var source = runs.runs.get(waiting.sourceRunId());
        AgentDataPersistenceState policy = new AgentDataPersistenceState("hidden")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER).markRuntimeDispatchStarted();
        runs.save(source.withMetadata(policy.runMetadataOverlay()));
        List<ChatEvent> stored = respond(waiting, true, Map.of("label", Map.of("期间", "本月")));
        assertThat(stored.getLast().type()).isEqualTo("run.completed");
        assertThat(stored).noneMatch(event -> "message.delta".equals(event.type()));
        assertThat(stored).anyMatch(event -> "clarification-response".equals(event.payload().get("sourceType")));
        assertThat(messages.messages).filteredOn(message -> waiting.assistantMessageId().equals(message.id()))
                .singleElement().satisfies(message -> {
                    assertThat(message.content()).isEqualTo("hidden");
                    assertThat(message.parts().toString()).doesNotContain("after", DomainAgentQuestionnaireContext.KEY);
                });
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.ANSWERED);
    }

    @Test
    void refusalAfterAnswerUsesFrozenInputAndExistingReroutePipeline() {
        ChatInteractionRequest waiting = start();
        refuseAnswer();
        List<ChatEvent> result = respond(waiting, true, Map.of("label", Map.of("期间", "本月")));
        assertThat(result.getLast().type()).isEqualTo("run.completed");
        assertThat(queries).hasValue(2);
        assertThat(rerouteRequest.get().command().message()).contains("original question", "期间", "本月");
        assertThat(rerouteRequest.get().command().intentAccessName()).isEqualTo("saved-entry");
        assertThat(query.get().messageId()).isEqualTo(waiting.userMessageId());
        assertThat(messages.messages).filteredOn(message -> "assistant".equals(message.role())).singleElement()
                .satisfies(message -> assertThat(message.content()).isEqualTo("rerouted"));
        assertThat(result).extracting(ChatEvent::type).contains("runtime.metadata");
    }

    @Test
    void refusalAfterAnswerCanRequestDomainAgentRouteSwitch() {
        ChatInteractionRequest waiting = start();
        refuseAnswer();
        rerouteResult.set(RouteSignalResult.of(RouteTarget.domainAgent("skill-b", "intent-agent", 1.0, "different")));
        assertThat(respond(waiting, true, Map.of("label", Map.of("期间", "本月"))).getLast().type())
                .isEqualTo("run.waiting_user");
        assertThat(interactions.requests.values()).anySatisfy(request -> {
            assertThat(request.requestPayload()).containsEntry("candidateTargetId", "skill-b");
            assertThat(request.status()).isEqualTo(ChatInteractionStatus.WAITING);
        });
        assertThat(queries).hasValue(1);
    }

    @Test
    void refusalAfterAnswerCanFallBackToRelayWithoutResubmittingAnswer() {
        ChatInteractionRequest waiting = start();
        refuseAnswer();
        rerouteResult.set(RouteSignalResult.of(RouteTarget.agentRuntime("intent-agent")));
        // 手动来源先确认切换是原有语义，问卷续跑不能绕过该确认。
        assertThat(respond(waiting, true, Map.of("label", Map.of("期间", "本月"))).getLast().type())
                .isEqualTo("run.waiting_user");
        assertThat(interactions.requests.values()).anySatisfy(request ->
                assertThat(request.requestPayload()).containsEntry("candidateProvider", "relay"));
    }

    private void refuseAnswer() {
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(),
                    "data: {\"type\":\"agent.refusal\",\"code\":\"FN-EX-CAHT-BIZ-DAG-001\","
                            + "\"reason\":\"outside domain\",\"reasonCode\":\"OUT_OF_DOMAIN\"}\n\n"));
        });
    }

    @Test
    void failureBeforeSubmissionRestoresWaitingAndOriginalBinding() {
        ChatInteractionRequest waiting = start();
        answer = request -> Flux.error(new IllegalStateException("not submitted"));
        assertThat(respond(waiting, false, Map.of("ignore", true)).getLast().type()).isEqualTo("run.failed");
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
        assertThat(bindings.saved.lastRunId()).isEqualTo(waiting.sourceRunId());
    }

    @Test
    void failureAfterSubmissionCancelsInteractionAndBindingWithoutRetry() {
        ChatInteractionRequest waiting = start();
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.error(new IllegalStateException("response lost"));
        });
        assertThat(respond(waiting, false, Map.of("ignore", true)).getLast().type()).isEqualTo("run.failed");
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.CANCELLED);
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.CANCELLED);
        assertThat(queries).hasValue(1);
    }

    @Test
    void answerHttpWaitsForResponseEventPersistence() throws InterruptedException {
        ChatInteractionRequest waiting = start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        beforeAnswerAppend.set(event -> {
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test persistence barrier timed out");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });
        ChatRunStartResult started;
        try {
            started = startResponse(waiting, false, Map.of("ignore", true));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(submitted.get()).isNull();
            assertThat(events.events).noneMatch(event -> started.runId().equals(event.runId())
                    && "clarification-response".equals(event.payload().get("sourceType")));
        } finally {
            release.countDown();
        }
        assertThat(awaitTerminal(started).getLast().type()).isEqualTo("run.completed");
        assertThat(submitted.get()).isNotNull();
        assertThat(queries).hasValue(1);
    }

    @Test
    void failedAnswerPersistenceNeverSendsControlAndRestoresWaiting() {
        ChatInteractionRequest waiting = start();
        beforeAnswerAppend.set(event -> { throw new IllegalStateException("test event write failed"); });
        assertThat(respond(waiting, false, Map.of("ignore", true)).getLast().type()).isEqualTo("run.failed");
        assertThat(submitted.get()).isNull();
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(bindings.saved.lastRunId()).isEqualTo(waiting.sourceRunId());
        assertThat(bindings.saved.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);
    }

    @Test
    void stopDuringAnswerStreamKeepsAlreadyCommittedPrefix() {
        ChatInteractionRequest waiting = start();
        answer = request -> Flux.defer(() -> {
            request.dispatchState().markResponseDispatched();
            return Flux.never();
        });
        ChatRunStartResult started = startResponse(waiting, false, Map.of("ignore", true));
        await().atMost(Duration.ofSeconds(5)).until(() -> submitted.get() != null
                && submitted.get().dispatchState().responseDispatched());
        service.stopRun(user, started.runId(), RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(runs.runs.get(started.runId()).status()).isEqualTo(ChatRunStatus.CANCELLED));
        assertThat(messages.messages).filteredOn(message -> message.id().equals(waiting.assistantMessageId()))
                .singleElement().satisfies(message -> assertThat(message.content()).isEqualTo("before"));
        assertThat(queries).hasValue(1);
    }

    private ChatInteractionRequest start() {
        return start(new ChatCommand(null, null, null, null, null,
                "web", "original question", List.of(), Map.of("business", "private-value"), "DOMAIN_AGENT", "skill-a",
                ChatRunMode.NEXT, null, null, null, null, null, null, null, Map.of(),
                null, null, null, null, null, "saved-entry"));
    }

    private ChatInteractionRequest start(ChatCommand command) {
        List<ChatEvent> result = service.executeRun(user, command).collectList().block(Duration.ofSeconds(10));
        assertThat(result.getLast().type()).isEqualTo("run.waiting_user");
        return interactions.requests.values().iterator().next();
    }

    private List<ChatEvent> respond(ChatInteractionRequest waiting, boolean approved, Map<String, Object> answers) {
        return awaitTerminal(startResponse(waiting, approved, answers));
    }

    private ChatRunStartResult startResponse(ChatInteractionRequest waiting, boolean approved, Map<String, Object> answers) {
        var started = service.startRun(user, new ChatCommand(null, null, null, waiting.sessionId(), null, "web", null,
                List.of(), Map.of(), null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null,
                null, waiting.id(), approved, "once", answers), RuntimeForwardHeaders.empty())
                .block(Duration.ofSeconds(10));
        assertThat(started.userMessageId()).isEqualTo(waiting.userMessageId());
        return started;
    }

    private List<ChatEvent> awaitTerminal(ChatRunStartResult started) {
        await().atMost(Duration.ofSeconds(10)).until(() -> events.events.stream().anyMatch(event ->
                started.runId().equals(event.runId()) && List.of("run.completed", "run.failed", "run.waiting_user")
                        .contains(event.type())));
        return events.events.stream().filter(event -> started.runId().equals(event.runId())).toList();
    }

    private String prompt(String id) {
        return "data: {\"type\":\"approval-request\",\"approval_id\":\"" + id
                + "\",\"operation_type\":\"questionnaire\",\"mode\":\"questionnaire\","
                + "\"questions\":[{\"question\":\"期间\",\"options\":[{\"label\":\"本月\"}],\"multi_select\":false},"
                + "{\"question\":\"范围\",\"options\":[{\"label\":\"境内\"},{\"label\":\"境外\"}],\"multi_select\":true}]}\n\n";
    }
}
