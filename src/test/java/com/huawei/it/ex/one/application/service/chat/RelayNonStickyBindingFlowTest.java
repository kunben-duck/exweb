/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingCompletionPolicy;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.routing.RoutingPolicy;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.domain.runtime.RuntimeBindingStatus;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;
import com.huawei.it.ex.one.infrastructure.runtime.relay.RelayAgentRuntime;
import com.huawei.it.ex.one.infrastructure.runtime.relay.RelayRuntimeProtocolAdapter;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class RelayNonStickyBindingFlowTest extends ChatFlowTestSupport {
    private final InMemorySessionRepository sessions = new InMemorySessionRepository();
    private final InMemoryMessageRepository messages = new InMemoryMessageRepository();
    private final InMemoryRunRepository runs = new InMemoryRunRepository();
    private final InMemoryEventStore events = new InMemoryEventStore();
    private final MultiBindingRuntimeBindingRepository bindings = new MultiBindingRuntimeBindingRepository();
    private final InMemoryInteractionRequestRepository interactions = new InMemoryInteractionRequestRepository();
    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final AtomicInteger intentCalls = new AtomicInteger();
    private final AtomicInteger useCaseCalls = new AtomicInteger();
    private final AtomicReference<AgentRuntimeRequest> captured = new AtomicReference<>();
    private final AtomicReference<AgentRuntimeInteractionResponseRequest> answer = new AtomicReference<>();
    private boolean questionnaire;

    @Override
    RuntimeBindingCompletionPolicy bindingCompletionPolicy() {
        return new RuntimeBindingCompletionPolicy("analysis");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUTO", "MANUAL", "AGGREGATE", "LEGACY"})
    void completedSkillReroutesAndResumesTheSameDownstreamSession(String entry) {
        FinanceEXChatService service = service();
        ChatCommand firstCommand = firstCommand(entry);
        List<ChatEvent> first = service.executeRun(user, firstCommand).collectList().block();
        assertThat(first).isNotEmpty();
        assertThat(first.getLast().type()).isEqualTo("run.completed");
        assertThat(captured.get().runtimeSessionMode()).isEqualTo(
                "LEGACY".equals(entry) ? RuntimeSessionMode.RESUME : RuntimeSessionMode.NEW);
        RuntimeBinding firstBinding = bindings.bindingsForProvider("relay").getFirst();
        assertThat(firstBinding.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
        assertThat(firstBinding.runtimeSessionId()).isEqualTo("real-relay-session");
        assertThat(firstBinding.runtimeSessionId()).isNotEqualTo(firstBinding.chatSessionId());
        assertThat(firstBinding.metadata()).containsEntry("runtimeProfile", "DOMAIN_EXPERT")
                .containsEntry("relayRoleName", "analysis");
        if ("MANUAL".equals(entry)) {
            assertThat(firstBinding.metadata()).containsEntry("relayExpertPinned", true);
        }
        int expectedFirstCalls = "MANUAL".equals(entry) || "LEGACY".equals(entry) ? 0 : 1;
        assertThat(intentCalls).hasValue(expectedFirstCalls);
        assertNoActiveBindingInStreamStatus(firstBinding.chatSessionId());

        List<ChatEvent> next = service.executeRun(user, new ChatCommand(
                "next", null, null, firstBinding.chatSessionId(), null, "web", "Next question",
                List.of(), Map.of())).collectList().block();

        assertThat(next).isNotEmpty();
        assertThat(next.getLast().type()).isEqualTo("run.completed");
        assertThat(intentCalls).hasValue(expectedFirstCalls + 1);
        assertThat(useCaseCalls).hasValue("AGGREGATE".equals(entry) ? 0 : expectedFirstCalls + 1);
        assertThat(captured.get().runtimeSessionMode()).isEqualTo(RuntimeSessionMode.RESUME);
        assertThat(captured.get().runtimeSessionId()).isEqualTo("real-relay-session");
        assertThat(captured.get().routeTarget().runtimeRoleName()).isEqualTo("analysis");
        assertThat(captured.get().routeTarget().invocationSkillId()).isEqualTo("RE_analysis");
        assertThat(captured.get().routeTarget().routeSource()).isEqualTo(
                "AGGREGATE".equals(entry) ? "intent-expert" : "intent-agent");
        assertThat(bindings.bindingsForProvider("relay")).singleElement().satisfies(binding -> {
            assertThat(binding.id()).isEqualTo(firstBinding.id());
            assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
        });
        assertNoActiveBindingInStreamStatus(firstBinding.chatSessionId());
    }

    @Test
    void questionnaireKeepsActiveUntilAnswerCompletesWithoutAnotherIntent() {
        questionnaire = true;
        FinanceEXChatService service = service();
        List<ChatEvent> waitingEvents = service.executeRun(user, firstCommand("AUTO")).collectList().block();
        assertThat(waitingEvents).isNotEmpty();
        assertThat(waitingEvents.getLast().type()).isEqualTo("run.waiting_user");
        var waiting = interactions.requests.values().iterator().next();
        assertThat(waiting.status()).isEqualTo(ChatInteractionStatus.WAITING);
        assertThat(waiting.runtimeSessionId()).isEqualTo("real-relay-session");
        RuntimeBinding before = bindings.bindingsForProvider("relay").getFirst();
        assertThat(before.status()).isEqualTo(RuntimeBindingStatus.ACTIVE);

        ChatCommand response = new ChatCommand("answer", null, null, waiting.sessionId(), null, "web", null,
                List.of(), Map.of(), null, null, ChatRunMode.CONTINUE_INTERACTION, null, null, null, null,
                waiting.id(), true, "once", Map.of("label", Map.of("Period?", "Month")));
        assertThat(service.startRun(user, response).block(Duration.ofSeconds(5))).isNotNull();
        awaitEvent(events, "run.completed");
        awaitInteractionStatus(interactions, waiting.id(), ChatInteractionStatus.ANSWERED);
        assertThat(intentCalls).hasValue(1);
        assertThat(answer.get()).isNotNull();
        assertThat(answer.get().runtimeSessionId()).isEqualTo("real-relay-session");
        assertThat(bindings.bindingsForProvider("relay")).singleElement().satisfies(binding -> {
            assertThat(binding.id()).isEqualTo(before.id());
            assertThat(binding.status()).isEqualTo(RuntimeBindingStatus.RESUMABLE);
            assertThat(binding.runtimeSessionId()).isEqualTo("real-relay-session");
        });
        assertThat(interactions.requests.get(waiting.id()).status()).isEqualTo(ChatInteractionStatus.ANSWERED);
    }

    private ChatCommand firstCommand(String entry) {
        if ("LEGACY".equals(entry)) {
            Instant now = Instant.now();
            sessions.save(new ChatSession("legacy-session", "tenant1", "user1", "Title", "ACTIVE", "web", now, now));
            bindings.save(new RuntimeBinding("legacy-binding", "tenant1", "user1", "legacy-session", "relay",
                    null, "real-relay-session", RuntimeBindingStatus.ACTIVE, "legacy-run", null, now, now,
                    RuntimeProfileMetadata.bindingMetadata(RuntimeProfile.DOMAIN_EXPERT,
                            "delegate", "domain_expert", "analysis")));
        }
        String targetType = switch (entry) {
            case "MANUAL" -> "DOMAIN_EXPERT";
            case "AGGREGATE" -> "INTENT_EXPERT";
            default -> null;
        };
        String targetId = "MANUAL".equals(entry) ? "analysis" : "AGGREGATE".equals(entry) ? "expert-a" : null;
        ChatCommand command = new ChatCommand("first", null, null,
                "LEGACY".equals(entry) ? "legacy-session" : null, null, "web", "Analyze",
                List.of(), Map.of(), targetType, targetId, ChatRunMode.NEXT, null, null, null);
        return "AGGREGATE".equals(entry)
                ? command.withIntentExpertScope(new IntentExpertScope("expert-a", "Expert A", "entry_a")) : command;
    }

    private FinanceEXChatService service() {
        RouteSignalApplicationService routing = new RouteSignalApplicationService(
                request -> {
                    useCaseCalls.incrementAndGet();
                    return UseCaseMatchResult.notMatched("not matched");
                }, intentAgent((command, memory, routeUser) -> {
                    intentCalls.incrementAndGet();
                    return new IntentDecision("analysis", "Analysis", TaskComplexity.SIMPLE, 0.99, true,
                            "RE_analysis", Map.of("routeAction", "ROUTE_SINGLE"), List.of(), Map.of());
                }), new RoutingPolicy(0.85, 0.85, "RE_"), new RouteSignalProperties(true, true));
        RelayAgentRuntime relay = new RelayAgentRuntime(new RelayRuntimeProtocolAdapter() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                captured.set(request);
                assertThat(bindings.bindingsForProvider("relay"))
                        .filteredOn(binding -> binding.status() == RuntimeBindingStatus.ACTIVE).hasSize(1);
                // 先确认真实会话ID，再模拟后续业务输出，避免同步测试源提前生成终态快照。
                Sinks.One<Void> sessionReady = Sinks.one();
                ChatEvent ready = new PersistenceAcknowledgedEvent(RuntimeEvent.metadata(
                        request.runId(), request.sessionId(), Map.of("source", "relay", "sourceType", "session-ready",
                                "runtimeSessionId", "real-relay-session")), sessionReady);
                ChatEvent output = questionnaire ? RuntimeEvent.card(request.runId(), request.sessionId(), Map.of(
                                "source", "relay", "sourceType", "approval-request", "approval_id", "approval-1",
                                "operation_type", "questionnaire", "mode", "questionnaire", "message", "Choose period",
                                "questions", List.of(Map.of("question", "Period?", "multi_select", false,
                                        "options", List.of(Map.of("label", "Month"))))))
                                : MessageSnapshotEvent.of(request.runId(), request.sessionId(), "Answer");
                return Flux.concat(Flux.just(ready), sessionReady.asMono().thenReturn(output));
            }

            @Override
            public boolean supportsUserResponseContinuation() {
                return true;
            }

            @Override
            public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
                answer.set(request);
                return Flux.just(MessageSnapshotEvent.of(request.runId(), request.sessionId(), "Continued answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        });
        return financeServiceWithDomainClientAndBindings(sessions, messages, runs, events, routing,
                refusingDomainAgentClient(), relay, bindings, new DomainAgentProperties(), liveEventBus(), interactions);
    }

    private void assertNoActiveBindingInStreamStatus(String sessionId) {
        RuntimeBindingApplicationService bindingService = new RuntimeBindingApplicationService(
                bindings, runtimeBindingCache(), new SequentialIdGenerator(), Duration.ZERO, "relay");
        var providers = new StaticListableBeanFactory(Map.of("bindings", bindingService));
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, new NeverCancelRunCache(),
                events, new PermissionChecker(), sessions, null, null, null,
                providers.getBeanProvider(RuntimeBindingApplicationService.class));
        var status = runService.streamStatus(user, sessionId);
        assertThat(status.activeRunId()).isNull();
        assertThat(status.bindingTargetId()).isNull();
        assertThat(status.bindingTargetType()).isNull();
    }
}
