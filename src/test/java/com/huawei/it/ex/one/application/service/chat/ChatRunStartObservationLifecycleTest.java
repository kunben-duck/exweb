/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static com.huawei.it.ex.one.application.service.observation.ObservationTestFixture.await;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.RouteSignalProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.service.observation.ObservationTestFixture;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalFrame;
import com.huawei.it.ex.one.application.service.routing.RouteSignalRequest;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class ChatRunStartObservationLifecycleTest extends ChatFlowTestSupport {
    @Test
    void actualBackgroundOwnerLossReleasesObservationWithoutFallbackTerminal() {
        try (var observation = new ObservationTestFixture()) {
            var sessions = new InMemorySessionRepository();
            var messages = new InMemoryMessageRepository();
            var runs = new InMemoryRunRepository();
            var events = new InMemoryEventStore();
            var executions = new InMemoryExecutionRepository();
            AtomicInteger runtimeCalls = new AtomicInteger();
            var routes = new RouteSignalApplicationService(
                    request -> UseCaseMatchResult.notMatched("disabled"),
                    intentAgent((command, memory, user) -> null),
                    new com.huawei.it.ex.one.domain.routing.RoutingPolicy(0.85),
                    new RouteSignalProperties(false, false)) {
                @Override public Flux<RouteSignalFrame> routeInitialWithProgress(RouteSignalRequest request) {
                    return Flux.defer(() -> {
                        assertThat(observation.turns.context(request.runId())).isNotNull();
                        executions.rejectOwnerRunningChecks = true;
                        return Flux.just(RouteSignalFrame.result(
                                RouteSignalResult.of(RouteTarget.agentRuntime("test-runtime"))));
                    });
                }
            };
            AgentRuntime runtime = new AgentRuntime() {
                @Override public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                    runtimeCalls.incrementAndGet();
                    return Flux.empty();
                }
                @Override public Mono<Void> cancel(AgentRuntimeCancelRequest request) { return Mono.empty(); }
            };
            var service = defaultFinanceService(sessions, messages, runs, events, routes, runtime, true,
                    new NeverCancelRunCache(), executions);
            observation.wire(service);
            var started = service.startRun(new UserContext("tenant1", "user1", "User One"),
                    new ChatCommand("command", null, null, null, null, "web", "hello", List.of(), Map.of()))
                    .block(Duration.ofSeconds(5));
            assertThat(started).isNotNull();
            await(() -> observation.turns.context(started.runId()) == null);
            await(() -> observation.traces.size() >= 2);
            assertThat(runtimeCalls).hasValue(0);
            assertThat(events.events).extracting(ChatEvent::type).containsExactly("run.started");
            assertThat(observation.traces).noneMatch(trace -> "FAILED".equals(trace.status())
                    || "CANCELLED".equals(trace.status()) || "COMPLETED".equals(trace.status()));
        }
    }
}
