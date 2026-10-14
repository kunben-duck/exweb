/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventAppendRejectedException;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceGate;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RoutingPolicy;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

class BeforeIntentRouteSwitchFlowTest extends ChatFlowTestSupport {
    private final UserContext user = new UserContext("tenant1", "user1", "tester");
    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
    private final CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
    private final InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
    private final DomainAgentProperties properties = new DomainAgentProperties();
    private final AtomicInteger intents = new AtomicInteger();
    private final AtomicInteger queries = new AtomicInteger();
    private Consumer<ChatEvent> beforeAppend = event -> { };
    private final InMemoryEventStore events = new InMemoryEventStore() {
        @Override
        public ChatEvent appendWithExecutionGuard(ChatEvent event, RunExecutionClaim claim) {
            beforeAppend.accept(event);
            return super.appendWithExecutionGuard(event, claim);
        }

        @Override
        public List<ChatEvent> sequenceLiveBatchWithExecutionGuard(List<ChatEvent> batch, RunExecutionClaim claim) {
            return batch.stream().map(event -> (ChatEvent) new StoredChatEvent(event.runId(), event.sessionId(),
                    ++seq, event.type(), Instant.now(), event.payload())).toList();
        }
    };
    private RouteSignalResult result = RouteSignalResult.of(RouteTarget.domainAgent("agent-b", "intent-agent"));
    private boolean alwaysRefuse;
    private boolean noStore;
    private IntentExpertScope expert;
    private FinanceEXChatService service;
    private LocalChatRunExecutionRegistry registry;

    @BeforeEach
    void setup() {
        RouteSignalApplicationService routes = new RouteSignalApplicationService(
                request -> UseCaseMatchResult.notMatched("disabled"),
                intentAgent((command, memory, owner) -> null), new RoutingPolicy(0.85),
                new RouteSignalProperties(false, false)) {
            @Override
            public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                if (expert != null) {
                    return Flux.just(RouteSignalFrame.result(RouteSignalResult.of(
                            RouteTarget.domainAgent("agent-a", "user-confirmed", 1.0, "selected expert skill"))));
                }
                throw new AssertionError("confirmed reroute must bypass initial routing");
            }

            @Override
            public Flux<RouteSignalFrame> routeIntentWithProgress(RouteSignalRequest request) {
                return Flux.defer(() -> {
                    intents.incrementAndGet();
                    if (expert != null) {
                        assertThat(request.command().intentExpertScope()).isEqualTo(expert);
                        assertThat(request.command().intentAccessName()).isEqualTo(expert.intentAccessName());
                    }
                    return Flux.just(RouteSignalFrame.event(RuntimeEvent.metadata(request.runId(),
                                    request.session().id(), Map.of("sourceType", "intent-result"))),
                            RouteSignalFrame.result(result));
                });
            }
        };
        DomainAgentClient agent = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                if (queries.incrementAndGet() == 1 || alwaysRefuse) {
                    return Flux.just(domainAgentRefusalEvent(request.runId(), request.sessionId()));
                }
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "answer"));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) { return Mono.empty(); }
        };
        AgentDataPersistenceGate gate = new AgentDataPersistenceGate(null, null, properties) {
            @Override
            public Mono<Decision> evaluate(UserContext owner, RouteTarget route, AgentDataPersistenceState state,
                                           RuntimeForwardHeaders headers, List<UploadedDocument> documents) {
                return Mono.just(Decision.allowed(state.tighten(noStore
                        ? AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER : AgentDataPersistencePolicy.FULL)));
            }
        };
        service = financeServiceWithDomainClientAndBindings(sessions, messages, runs, events, routes,
                agent, noopRuntime(), bindings, properties, liveEventBus(), interactions, runtimeBindingCache(),
                Schedulers.boundedElastic(), documentFacade(), executions, gate);
        Object orchestrator = ReflectionTestUtils.getField(service, "orchestrator");
        ChatRunStopCoordinator stop = (ChatRunStopCoordinator) ReflectionTestUtils.getField(orchestrator, "stopCoordinator");
        registry = (LocalChatRunExecutionRegistry) ReflectionTestUtils.getField(stop, "runExecutionRegistry");
        stop.setWaitingStopCommitService(new ChatWaitingStopCommitService(
                (SessionApplicationService) ReflectionTestUtils.getField(stop, "sessionService"), interactions,
                runs, bindings, new com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService(
                bindings, runtimeBindingCache(), new SequentialIdGenerator(), Duration.ZERO, "relay"),
                org.mockito.Mockito.mock(com.huawei.it.ex.one.application.integration.memory.RouteMemoryRepository.class)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"agent-a", "agent-b"})
    void approvalCallsIntentOnceEvenForSameTargetAndReusesMessages(String skill) {
        ChatInteractionRequest waiting = start();
        ChatEvent originalWait = events.events.stream().filter(event -> waiting.sourceRunId().equals(event.runId())
                && "run.waiting_user".equals(event.type())).findFirst().orElseThrow();
        assertThat(originalWait.payload().get("runDurationMs")).isInstanceOf(Long.class);
        messages.messages.replaceAll(message -> message.id().equals(waiting.assistantMessageId())
                ? message.withMetadataJson(new RunDuration(99999999L).mergeMetadata(new ObjectMapper(), message.metadataJson()))
                : message);
        result = RouteSignalResult.of(RouteTarget.domainAgent(skill, "intent-agent"));
        List<ChatEvent> output = execute(continuation(waiting, true, Map.of(), null, null));
        assertThat(output.getLast().type()).isEqualTo("run.completed");
        assertThat(intents).hasValue(1);
        assertThat(queries).hasValue(2);
        assertThat(bindings.saved.metadata()).containsEntry("routeSource", "user-confirmed")
                .containsEntry("domainAgentId", skill);
        assertThat(messages.messages).hasSize(2);
        assertThat(messages.messages).anySatisfy(message -> {
            assertThat(message.id()).isEqualTo(waiting.assistantMessageId());
            assertThat(message.content()).isEqualTo("answer");
        });
        assertThat(output).extracting(event -> event.payload().get("sourceType"))
                .containsSubsequence("route-switch-confirmation-response", "intent-result", "route-switch-applied");
        ChatEvent terminal = output.getLast();
        long duration = ((Number) terminal.payload().get("runDurationMs")).longValue();
        assertThat(duration).isBetween(0L, 99999998L);
        assertThat(messages.messages.stream().filter(message -> message.id().equals(waiting.assistantMessageId())))
                .singleElement().satisfies(message -> assertThat(message.metadataJson())
                        .contains("\"runDurationMs\":" + duration));
        assertThat(events.events).contains(originalWait);
        assertThat(output.stream().filter(event -> !event.type().startsWith("run.")))
                .allSatisfy(event -> assertThat(event.payload()).doesNotContainKey("runDurationMs"));
        assertNoPrivateEvents();
    }

    @Test
    void repeatedRefusalAsksAgainAndKeepsBudgetAcrossRuns() {
        properties.setMaxReroutes(2);
        alwaysRefuse = true;
        ChatInteractionRequest first = start();
        assertThat(execute(continuation(first, true, Map.of(), null, null)).getLast().type())
                .isEqualTo("run.waiting_user");
        ChatInteractionRequest second = waiting();
        assertThat(RouteSwitchRerouteContext.require(second).state().rerouteCount()).isEqualTo(1);
        assertThat(intents).hasValue(1);
        assertThat(execute(continuation(second, true, Map.of(), null, null)).getLast().type())
                .isEqualTo("run.completed");
        assertThat(intents).hasValue(2);
        assertThat(queries).hasValue(3);
        assertThat(interactions.requests.values()).noneMatch(ChatInteractionRequest::waiting);
        assertThat(events.events).anySatisfy(event ->
                assertThat(event.payload()).containsEntry("action", "MAX_REROUTES_REACHED"));
    }

    @Test
    void declineDoesNotRouteAndKeepsOriginalBinding() {
        ChatInteractionRequest waiting = start();
        String id = bindings.saved.id();
        assertThat(execute(continuation(waiting, false, Map.of(), null, null)).getLast().type())
                .isEqualTo("run.completed");
        assertThat(intents).hasValue(0);
        assertThat(queries).hasValue(1);
        assertThat(bindings.saved.id()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLARIFY", "OTHER", "SELECT"})
    void authorizationSurvivesClarificationAndAmbiguousSelection(String mode) {
        ChatInteractionRequest first = start();
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("originalQuery", "original query",
                "clarifyQuestion", "which period?", "routeAction", "CLARIFY"));
        if (!"CLARIFY".equals(mode)) {
            payload.put("type", "AMBIGUOUS_ROUTE");
            payload.put("clarificationType", "AMBIGUOUS_ROUTE");
            payload.put("candidateIntents", List.of(Map.of("intentId", "intent-b", "intentName", "Agent B",
                    "skillId", "agent-b", "confidence", 0.9)));
        }
        result = RouteSignalResult.waitingIntentClarification(payload, 1L, 0.85, "intent-session", "intent-request");
        assertThat(execute(continuation(first, true, Map.of(), null, null)).getLast().type())
                .isEqualTo("run.waiting_user");
        ChatInteractionRequest clarification = waiting();
        assertThat(clarification.interactionType()).isEqualTo(ChatInteractionType.INTENT_CLARIFICATION);
        assertNoPrivateEvents();
        result = RouteSignalResult.of(RouteTarget.domainAgent("agent-b", "intent-agent"));
        ChatCommand answer = continuation(clarification, null,
                "SELECT".equals(mode) ? Map.of() : Map.of("which period?", "this month"),
                "SELECT".equals(mode) ? "agent-b" : null, null);
        assertThat(execute(answer).getLast().type()).isEqualTo("run.completed");
        assertThat(intents).hasValue("SELECT".equals(mode) ? 1 : 2);
        assertThat(queries).hasValue(2);
        assertThat(bindings.saved.metadata()).containsEntry("routeSource", "user-confirmed");
        assertNoPrivateEvents();
    }

    @ParameterizedTest
    @ValueSource(strings = {"route-switch-confirmation-response", "intent-result"})
    void persistenceBarrierPreventsDownstreamWhileAppendIsBlocked(String sourceType) throws Exception {
        ChatInteractionRequest waiting = start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        beforeAppend = event -> {
            if (sourceType.equals(event.payload().get("sourceType"))) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) { throw new IllegalStateException("test timeout"); }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
        };
        ChatRunStartResult started;
        try {
            started = service.startRun(user, continuation(waiting, true, Map.of(), null, null),
                    RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(intents).hasValue("intent-result".equals(sourceType) ? 1 : 0);
            assertThat(queries).hasValue(1);
            assertThat(bindings.saved.metadata()).containsEntry("domainAgentId", "agent-a");
        } finally { release.countDown(); }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(events.events)
                .anyMatch(event -> started.runId().equals(event.runId()) && "run.completed".equals(event.type())));
        assertThat(queries).hasValue(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"response-failure", "intent-failure"})
    void failedAcknowledgementOrLostOwnerCannotCallRuntime(String failure) {
        ChatInteractionRequest waiting = start();
        beforeAppend = event -> {
            String source = String.valueOf(event.payload().get("sourceType"));
            if ("route-switch-confirmation-response".equals(source)) {
                if ("response-failure".equals(failure)) { throw new IllegalStateException("append failed"); }
            }
            if ("intent-result".equals(source) && "intent-failure".equals(failure)) {
                throw new IllegalStateException("intent append failed");
            }
        };
        execute(continuation(waiting, true, Map.of(), null, null));
        assertThat(intents).hasValue("intent-failure".equals(failure) ? 1 : 0);
        assertThat(queries).hasValue(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rejectedWriteOrOwnerLossReleasesSubscriptionWithoutIntent(boolean ownerLoss) {
        ChatInteractionRequest first = start();
        beforeAppend = event -> {
            if ("route-switch-confirmation-response".equals(event.payload().get("sourceType"))) {
                if (ownerLoss) {
                    executions.rejectOwnerRunningChecks = true;
                } else {
                    throw new ChatEventAppendRejectedException("test lost write fence");
                }
            }
        };
        ChatRunStartResult started = service.startRun(user, continuation(first, true, Map.of(), null, null),
                RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(registry.activeClaims()).noneMatch(claim -> started.runId().equals(claim.runId())));
        assertThat(intents).hasValue(0);
        assertThat(queries).hasValue(1);
    }

    @Test
    void unknownStageIsRejectedBeforeClaimButLegacyCandidateStillExecutes() {
        ChatInteractionRequest first = start();
        Map<String, Object> payload = new LinkedHashMap<>(first.requestPayload());
        payload.put("confirmationStage", "UNKNOWN");
        replacePayload(first, payload);
        assertThatThrownBy(() -> execute(continuation(first, true, Map.of(), null, null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("确认阶段");
        assertThat(interactions.claimCalls).hasValue(0);
        payload.remove("confirmationStage");
        payload.remove(RouteSwitchRerouteContext.PRIVATE_KEY);
        payload.put("candidateProvider", "domain-agent");
        payload.put("candidateTargetId", "agent-b");
        replacePayload(first, payload);
        assertThat(execute(continuation(first, true, Map.of(), null, null)).getLast().type())
                .isEqualTo("run.completed");
        assertThat(intents).hasValue(0);
        assertThat(queries).hasValue(2);
    }

    @Test
    void duplicateApprovalCannotRestartIntent() {
        ChatInteractionRequest first = start();
        ChatCommand approval = continuation(first, true, Map.of(), null, null);
        execute(approval);
        assertThatThrownBy(() -> execute(approval)).isInstanceOf(IllegalStateException.class);
        assertThat(intents).hasValue(1);
    }

    @Test
    void stopWhileWaitingPreventsApproval() {
        ChatInteractionRequest first = start();
        ChatEvent waitingEvent = events.events.stream().filter(event -> first.sourceRunId().equals(event.runId())
                && "run.waiting_user".equals(event.type())).findFirst().orElseThrow();
        String waitingMetadata = messages.messages.stream().filter(message -> message.id().equals(first.assistantMessageId()))
                .findFirst().orElseThrow().metadataJson();
        assertThat(waitingEvent.payload().get("runDurationMs")).isInstanceOf(Long.class);
        service.stopRun(user, first.sourceRunId(), RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5));
        assertThat(messages.messages.stream().filter(message -> message.id().equals(first.assistantMessageId()))
                .findFirst().orElseThrow().metadataJson()).isEqualTo(waitingMetadata);
        assertThat(events.events).contains(waitingEvent);
        assertThatThrownBy(() -> execute(continuation(first, true, Map.of(), null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(intents).hasValue(0);
        assertThat(queries).hasValue(1);
    }

    @Test
    void expiredApprovalCannotCallIntent() {
        ChatInteractionRequest old = start();
        interactions.requests.put(old.id(), new ChatInteractionRequest(old.id(), old.tenantId(), old.userId(),
                old.sessionId(), old.sourceRunId(), old.continueRunId(), old.userMessageId(), old.assistantMessageId(),
                old.runtimeProvider(), old.runtimeBindingId(), old.runtimeSessionId(), old.approvalId(),
                old.interactionType(), old.status(), old.requestPayload(), old.responsePayload(), Instant.EPOCH,
                old.answeredAt(), old.cancelledAt(), old.createdAt(), old.updatedAt()));
        assertThatThrownBy(() -> execute(continuation(old, true, Map.of(), null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(intents).hasValue(0);
    }

    @Test
    void expertConfirmationKeepsSavedEntryAndBindingScope() {
        expert = new IntentExpertScope("expert-a", "Expert A", "expert_a_entry");
        ChatInteractionRequest first = start();
        assertThat(RouteSwitchRerouteContext.require(first).expertScope()).isEqualTo(expert);
        List<ChatEvent> output = execute(continuation(first, true, Map.of(), null, null));
        assertThat(output.getLast().type()).withFailMessage("events=%s", output).isEqualTo("run.completed");
        assertThat(intents).hasValue(1);
        assertThat(IntentExpertContext.fromMetadata(bindings.saved.metadata())).contains(expert);
        assertNoPrivateEvents();
    }

    @Test
    void changedExpertScopeRejectsOldApprovalWithoutCreatingRun() {
        ChatInteractionRequest first = start();
        ChatSession old = sessions.sessions.get(first.sessionId());
        String metadata = IntentExpertContext.replaceSessionMetadata(old.metadataJson(),
                new IntentExpertScope("expert-b", "Expert B", "entry-b"));
        sessions.save(new ChatSession(old.id(), old.tenantId(), old.userId(), old.title(), old.status(),
                old.channel(), old.appId(), old.appName(), old.currentLeafMessageId(), old.rootSessionId(),
                old.branchSourceSessionId(), old.branchSourceMessageId(), old.lastNodeOrder(),
                old.latestMessageSeq(), old.lastReadSeq(), metadata, old.createdAt(), old.updatedAt()));
        assertThatThrownBy(() -> execute(continuation(first, true, Map.of(), null, null)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(runs.runs).hasSize(1);
        assertThat(intents).hasValue(0);
    }

    @Test
    void failedIntentUsesExistingFailurePolicyAfterConsent() {
        ChatInteractionRequest first = start();
        result = RouteSignalResult.intentFailure(null, null, 10L, 0.85,
                new RouteSignalResult.IntentFailure(IntentFailureStrategy.FAIL_RUN, "test failure"));
        assertThat(execute(continuation(first, true, Map.of(), null, null)).getLast().type())
                .isEqualTo("run.failed");
        assertThat(intents).hasValue(1);
        assertThat(queries).hasValue(1);
        assertThat(interactions.requests.values()).noneMatch(ChatInteractionRequest::waiting);
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXPERT", "DELEGATE", "FALLBACK", "NO_TARGET"})
    void confirmedIntentKeepsRelayAndNoTargetCompletionRules(String mode) {
        ChatInteractionRequest first = start();
        result = switch (mode) {
            case "EXPERT" -> RouteSignalResult.of(RouteTarget.domainExpertRuntime(
                    "intent-agent", 0.95, "expert", "finance-expert", "actual-expert-skill"));
            case "DELEGATE" -> RouteSignalResult.of(RouteTarget.agentRuntime("delegate"));
            case "FALLBACK" -> RouteSignalResult.intentFailure(RouteTarget.agentRuntime("fallback"), null,
                    10L, 0.85, new RouteSignalResult.IntentFailure(IntentFailureStrategy.RELAY_FALLBACK, "failure"));
            default -> RouteSignalResult.of(RouteTarget.systemResponse("no target"));
        };
        List<ChatEvent> output = execute(continuation(first, true, Map.of(), null, null));
        assertThat(output.getLast().type()).isEqualTo("run.completed");
        assertThat(intents).hasValue(1);
        assertThat(queries).hasValue(1);
        assertThat(interactions.requests.values()).noneMatch(ChatInteractionRequest::waiting);
        if (!"NO_TARGET".equals(mode)) {
            assertThat(bindings.saved.provider()).isEqualTo("relay");
            if ("EXPERT".equals(mode)) {
                assertThat(bindings.saved.metadata()).containsEntry("routeSource", "user-confirmed")
                        .containsEntry("invocationSkillId", "actual-expert-skill");
            }
            assertThat(bindings.saved.status()).isEqualTo("EXPERT".equals(mode)
                    ? RuntimeBindingStatus.ACTIVE : RuntimeBindingStatus.RESUMABLE);
            assertThat(output).anyMatch(event -> "route-switch-applied".equals(event.payload().get("sourceType")));
        }
        assertNoPrivateEvents();
    }

    @Test
    void noStoreSourceStillPersistsConsentButNotNewBusinessOutput() {
        noStore = true;
        ChatInteractionRequest first = start();
        noStore = false;
        List<ChatEvent> output = execute(continuation(first, true, Map.of(), null, null));
        assertThat(output.getLast().type()).isEqualTo("run.completed");
        assertThat(intents).hasValue(1);
        assertThat(queries).hasValue(2);
        assertThat(output).noneMatch(event -> "message.snapshot".equals(event.type()));
        assertThat(messages.messages).noneMatch(message -> "answer".equals(message.content()));
        assertNoPrivateEvents();
    }

    private ChatInteractionRequest start() {
        ChatCommand command = new ChatCommand(null, null, null, null, null, "web", "original query",
                List.of(), Map.of(), expert == null ? "DOMAIN_AGENT" : "INTENT_EXPERT",
                expert == null ? "agent-a" : expert.expertId(), ChatRunMode.NEXT, null, null, null);
        List<ChatEvent> output = execute(command.withIntentExpertScope(expert));
        assertThat(output.getLast().type()).withFailMessage("queries=%s intents=%s events=%s",
                queries.get(), intents.get(), output).isEqualTo("run.waiting_user");
        assertThat(intents).hasValue(0);
        assertThat(waiting().requestPayload()).containsEntry("confirmationStage", "BEFORE_INTENT")
                .doesNotContainKeys("candidateTargetId", "candidateProvider");
        return waiting();
    }

    private ChatInteractionRequest waiting() {
        return interactions.requests.values().stream().filter(ChatInteractionRequest::waiting).findFirst().orElseThrow();
    }

    private ChatCommand continuation(ChatInteractionRequest interaction, Boolean approved,
                                     Map<String, Object> answers, String target, String action) {
        return new ChatCommand(null, null, null, interaction.sessionId(), null, "web", null, List.of(), Map.of(),
                target == null ? null : "DOMAIN_AGENT", target, ChatRunMode.CONTINUE_INTERACTION,
                null, null, null, null, interaction.id(), approved, null, answers, null, null, null, action);
    }

    private List<ChatEvent> execute(ChatCommand command) {
        ChatRunStartResult started = service.startRun(user, command, RuntimeForwardHeaders.empty())
                .block(Duration.ofSeconds(10));
        await().atMost(Duration.ofSeconds(10)).until(() -> events.events.stream().anyMatch(event ->
                started.runId().equals(event.runId()) && List.of("run.completed", "run.failed", "run.waiting_user")
                        .contains(event.type())));
        return events.events.stream().filter(event -> started.runId().equals(event.runId())).toList();
    }

    private void assertNoPrivateEvents() {
        assertThat(events.events).allSatisfy(event -> assertThat(event.payload().toString())
                .doesNotContain(RouteSwitchRerouteContext.PRIVATE_KEY, "confirmationInteractionId",
                        "domainAgentRerouteContext"));
    }

    private void replacePayload(ChatInteractionRequest old, Map<String, Object> payload) {
        interactions.requests.put(old.id(), new ChatInteractionRequest(old.id(), old.tenantId(), old.userId(),
                old.sessionId(), old.sourceRunId(), old.continueRunId(), old.userMessageId(), old.assistantMessageId(),
                old.runtimeProvider(), old.runtimeBindingId(), old.runtimeSessionId(), old.approvalId(),
                old.interactionType(), old.status(), payload, old.responsePayload(), old.expiresAt(),
                old.answeredAt(), old.cancelledAt(), old.createdAt(), old.updatedAt()));
    }
}
