/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.id.IdGenerator;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.memory.MemoryApplicationService;
import com.huawei.it.ex.one.application.service.observation.DefaultFirstBusinessFrameClassifier;
import com.huawei.it.ex.one.application.service.observation.ObservationTestFixture;
import com.huawei.it.ex.one.application.service.runtime.AgentRuntimeExecutor;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.SystemResponseExecutor;
import com.huawei.it.ex.one.application.service.runtime.WorkloadConcurrencyLimiter;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.observation.ObservationMetadata;
import com.huawei.it.ex.one.domain.observation.TurnTraceContext;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

class RelayQuestionnaireObservationFlowTest extends ChatFlowTestSupport {
    @ParameterizedTest
    @CsvSource({
        "false, false, 1, true, false, false",
        "true, false, 1, true, true, false",
        "false, true, 1, true, true, false",
        "true, true, 1, true, false, false",
        "false, false, 2, true, true, false",
        "true, false, 2, true, true, false",
        "false, false, 1, false, false, false",
        "false, true, 2, true, true, true",
        "true, false, 2, true, true, true"
    })
    void nullableQuestionnaireKeepsTraceAcrossAnswersAndInstances(
            boolean crossInstance, boolean ignore, int rounds, boolean enabled, boolean priorRefusal,
            boolean noStoreContinuation) {
        try (ObservationTestFixture first = observation(enabled);
                ObservationTestFixture second = observation(enabled)) {
            Fixture fixture = new Fixture(rounds, priorRefusal);
            FinanceEXChatService firstService = fixture.service(first, "instance-a");
            var root = firstService.startRun(fixture.user, new ChatCommand("root", null, null,
                    null, null, "web", "hello", List.of(), Map.of()), RuntimeForwardHeaders.empty())
                    .block(Duration.ofSeconds(5));
            assertThat(root).isNotNull();
            fixture.awaitFinishedSubscription(root.runId(), ChatRunStatus.WAITING_USER, first);
            ChatInteractionRequest waiting = fixture.waiting(root.runId());
            TurnTraceContext original = fixture.context(waiting);
            assertThat(waiting.requestPayload()).containsEntry("optionalDetail", null);
            if (enabled) {
                assertThat(original).isNotNull();
                assertThat(original.rootRunId()).isEqualTo(root.runId());
                assertThat(original.rootUserMessageId()).isEqualTo(root.userMessageId());
                assertThat(original.firstBusinessFrameAt()).isNotNull();
                assertThat(original.refusal()).isEqualTo(priorRefusal);
            } else {
                assertThat(original).isNull();
            }
            if (noStoreContinuation) {
                var policy = new AgentDataPersistenceState("hidden")
                        .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER).markRuntimeDispatchStarted();
                fixture.runs.save(fixture.runs.runs.get(root.runId()).withMetadata(policy.runMetadataOverlay()));
            }

            // 独立服务图共享业务仓储，不共享Coordinator内存；续跑只能从持久化Interaction恢复。
            ObservationTestFixture continuation = crossInstance ? second : first;
            FinanceEXChatService nextService = crossInstance
                    ? fixture.service(second, "instance-b") : firstService;
            for (int round = 1; round <= rounds; round++) {
                continuation.advance(Duration.ofSeconds(5));
                var next = nextService.startRun(fixture.user, new ChatCommand(null, null, null,
                        root.sessionId(), null, "web", null, List.of(), Map.of(), null, null,
                        ChatRunMode.CONTINUE_INTERACTION, null, null, null, null, waiting.id(), !ignore,
                        "once", ignore ? Map.of("ignore", true) : Map.of("label", Map.of("Choose", "A"))),
                        RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5));
                assertThat(next).isNotNull();
                boolean completed = round == rounds;
                fixture.awaitFinishedSubscription(next.runId(),
                        completed ? ChatRunStatus.COMPLETED : ChatRunStatus.WAITING_USER, continuation);
                assertThat(next.userMessageId()).isEqualTo(root.userMessageId());
                assertThat(fixture.interactions.requests.get(waiting.id()).status())
                        .isEqualTo(ChatInteractionStatus.ANSWERED);
                TurnTraceContext sent = fixture.answers.get(round - 1).turnTraceContext();
                if (enabled) {
                    assertInherited(sent, original, round + 1);
                    assertThat(sent.retainText()).isEqualTo(!noStoreContinuation);
                    if (completed) {
                        ObservationTestFixture.await(() -> continuation.traces.stream()
                                .anyMatch(trace -> "COMPLETED".equals(trace.status())));
                        assertThat(continuation.traces).filteredOn(trace -> "COMPLETED".equals(trace.status()))
                                .singleElement().satisfies(trace -> {
                                    assertThat(trace.traceId()).isEqualTo(original.traceId());
                                    assertThat(trace.currentRunId()).isEqualTo(next.runId());
                                    assertThat(trace.startedAt()).isEqualTo(original.startedAt());
                                    assertThat(trace.firstBusinessFrameAt()).isEqualTo(original.firstBusinessFrameAt());
                                    assertThat(trace.firstBusinessFrameLatencyMs())
                                            .isEqualTo(original.firstBusinessFrameLatencyMs());
                                    assertThat(trace.isRefusal()).isEqualTo(priorRefusal);
                                    assertThat(trace.durationMs()).isGreaterThanOrEqualTo(5000L);
                                });
                    }
                } else {
                    assertThat(sent).isNull();
                }
                if (!completed) {
                    waiting = fixture.waiting(next.runId());
                    assertThat(waiting.requestPayload()).containsEntry("optionalDetail", null);
                    assertInherited(fixture.context(waiting), original, round + 1);
                }
            }
            assertThat(fixture.answers).hasSize(rounds);
            assertThat(fixture.events.events.toString()).doesNotContain(ObservationMetadata.KEY);
            assertThat(fixture.messages.messages.toString()).doesNotContain(ObservationMetadata.KEY);
            var replay = fixture.replay(root.runId());
            assertThat(replay).filteredOn(event -> "approval-request".equals(event.payload().get("sourceType")))
                    .singleElement().satisfies(event -> assertThat(event.payload()).containsEntry("optionalDetail", null));
            assertThat(replay.toString()).doesNotContain(ObservationMetadata.KEY);
            if (enabled) {
                assertThat(first.traces).allSatisfy(trace -> assertThat(trace.traceId()).isEqualTo(original.traceId()));
                assertThat(second.traces).allSatisfy(trace -> assertThat(trace.traceId()).isEqualTo(original.traceId()));
                assertThat(fixture.events.events.toString()).doesNotContain(original.traceId());
                assertThat(fixture.messages.messages.toString()).doesNotContain(original.traceId());
                assertThat(replay.toString()).doesNotContain(original.traceId());
            } else {
                assertThat(first.traces).isEmpty();
                assertThat(first.calls).isEmpty();
            }
        }
    }

    private static ObservationTestFixture observation(boolean enabled) {
        return new ObservationTestFixture(enabled, new DefaultFirstBusinessFrameClassifier());
    }

    private static void assertInherited(TurnTraceContext actual, TurnTraceContext root, long ordinal) {
        assertThat(actual).isNotNull();
        assertThat(actual.traceId()).isEqualTo(root.traceId());
        assertThat(actual.originType()).isEqualTo(root.originType());
        assertThat(actual.rootRunId()).isEqualTo(root.rootRunId());
        assertThat(actual.rootUserMessageId()).isEqualTo(root.rootUserMessageId());
        assertThat(actual.startedAt()).isEqualTo(root.startedAt());
        assertThat(actual.firstBusinessFrameAt()).isEqualTo(root.firstBusinessFrameAt());
        assertThat(actual.firstBusinessFrameLatencyMs()).isEqualTo(root.firstBusinessFrameLatencyMs());
        assertThat(actual.refusal()).isEqualTo(root.refusal());
        assertThat(actual.runOrdinal()).isEqualTo(ordinal);
    }

    private final class Fixture {
        final InMemorySessionRepository sessions = new InMemorySessionRepository();
        final InMemoryMessageRepository messages = new InMemoryMessageRepository();
        final InMemoryRunRepository runs = new InMemoryRunRepository();
        final InMemoryEventStore events = new InMemoryEventStore();
        final InMemoryExecutionRepository executions = new InMemoryExecutionRepository();
        final InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
        final CapturingRuntimeBindingRepository bindings = new CapturingRuntimeBindingRepository();
        final IdGenerator ids = new SequentialIdGenerator();
        final UserContext user = new UserContext("tenant1", "user1", "User One");
        final List<AgentRuntimeInteractionResponseRequest> answers = new CopyOnWriteArrayList<>();
        final int rounds;
        final boolean priorRefusal;

        Fixture(int rounds, boolean priorRefusal) {
            this.rounds = rounds;
            this.priorRefusal = priorRefusal;
        }

        ChatInteractionRequest waiting(String runId) {
            return interactions.requests.values().stream()
                    .filter(request -> runId.equals(request.sourceRunId())).findFirst().orElseThrow();
        }

        TurnTraceContext context(ChatInteractionRequest request) {
            return ObservationMetadata.read(request.requestPayload(), user.tenantId(), user.userId());
        }

        List<ChatEvent> replay(String runId) {
            var stream = new ChatStreamApplicationService(events, new LocalChatEventStreamRegistry(),
                    liveEventBus(), runs, new PermissionChecker(), sessions, new ChatWebSocketProperties());
            return stream.resumeRun(user, runId, 0).collectList().block(Duration.ofSeconds(5));
        }

        void awaitFinishedSubscription(String runId, ChatRunStatus status, ObservationTestFixture observation) {
            ObservationTestFixture.await(() -> runs.runs.get(runId).status() == status
                    && observation.turns.context(runId) == null);
        }

        RuntimeEvent questionnaire(String runId, String sessionId, String approvalId) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("source", "relay");
            payload.put("sourceType", "approval-request");
            payload.put("operation_type", "questionnaire");
            payload.put("approval_id", approvalId);
            payload.put("questions", List.of(Map.of("question", "Choose",
                    "options", List.of(Map.of("label", "A")), "multi_select", false)));
            payload.put("runtimeSessionId", sessionId);
            payload.put("optionalDetail", null);
            return RuntimeEvent.card(runId, sessionId, payload);
        }

        FinanceEXChatService service(ObservationTestFixture observation, String instanceId) {
            PermissionChecker permissions = new PermissionChecker();
            WorkloadConcurrencyLimiter limiter = new WorkloadConcurrencyLimiter(new ResourceIsolationProperties());
            LocalChatRunExecutionRegistry registry = new LocalChatRunExecutionRegistry();
            SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissions);
            ChatStreamApplicationService stream = new ChatStreamApplicationService(events,
                    new LocalChatEventStreamRegistry(), liveEventBus(), runs, permissions, sessions,
                    new ChatWebSocketProperties());
            ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(),
                    events, permissions, sessions);
            ChatRunLeaseApplicationService lease = new ChatRunLeaseApplicationService(executions,
                    (ApplicationInstanceIdProvider) () -> instanceId, new ChatRunOperationalProperties(), ids, registry);
            ChatInteractionApplicationService interactionService = new ChatInteractionApplicationService(
                    interactions, ids, permissions, new ChatInteractionProperties());
            AgentRuntime runtime = new AgentRuntime() {
                @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                    return Flux.defer(() -> {
                        observation.advance(Duration.ofMillis(20));
                        // 注入已有拒答采样，仅验证后续问卷不丢失累计事实，不模拟拒答路由。
                        if (priorRefusal) { observation.turns.sample(request.runId(), true, null); }
                        return Flux.just(questionnaire(request.runId(), request.sessionId(), "approval-1"));
                    });
                }
                @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
            };
            AgentRuntimeInteraction interaction = new AgentRuntimeInteraction() {
                @Override public boolean supportsWaitingUserResponse(String provider) { return "relay".equals(provider); }
                @Override public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
                    answers.add(request);
                    return answers.size() < rounds
                            ? Flux.just(questionnaire(request.runId(), request.sessionId(), "approval-2"))
                            : Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "Done"));
                }
            };
            AgentRuntimeExecutor relay = new AgentRuntimeExecutor(runtime, interaction, limiter);
            FinanceEXChatService service = ChatFlowTestFixture.service(sessionService,
                    new MemoryApplicationService(messages, longTermMemory(), new MemoryProperties()),
                    new RuntimeBindingApplicationService(bindings, runtimeBindingCache(), ids, Duration.ofDays(3), "relay"),
                    runtimeRouteService(), intentRecordService(), domainAgentExecutor(documentFacade(), limiter),
                    new SystemResponseExecutor(), relay, documentFacade(), stream, runService, lease,
                    new ChatDeltaCoalescer(new ChatStreamProperties()), registry,
                    new RunAdmissionControlService(new RunAdmissionProperties()),
                    new ChatRunStopCoordinator(sessionService, stream, runService, lease, registry, relay,
                            domainAgentExecutor(documentFacade(), limiter), ids), interactionService,
                    new ChatRunTerminalCommitService(stream, sessionService, runs, lease, bindings,
                            interactionService, Duration.ofDays(3)), ids, Schedulers.boundedElastic(),
                    new DomainAgentProperties());
            observation.wire(service);
            return service;
        }
    }
}
