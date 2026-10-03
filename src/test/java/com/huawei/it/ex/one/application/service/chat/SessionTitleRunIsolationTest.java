/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.SessionTitleProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class SessionTitleRunIsolationTest extends ChatFlowTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void runStartsRoutesAndCompletesWhileTitleIsPendingOrFails(boolean titleFails) throws Exception {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        SessionTitleProperties properties = new SessionTitleProperties();
        properties.setEnabled(true);
        SessionTitleMetadata metadata = new SessionTitleMetadata(new ObjectMapper(), properties);
        Instant now = Instant.now();
        sessions.save(new ChatSession("session_title", "tenant1", "user1", "initial title", "ACTIVE", "web",
                null, "session_title", null, null, 0L,
                metadata.initialize(null, SessionTitleSummarySource.AUTO), now, now));
        Scheduler titleIo = Schedulers.newBoundedElastic(1, 4, "title-run-isolation");
        Sinks.One<String> titleResponse = Sinks.one();
        Sinks.One<Void> runtimeRelease = Sinks.one();
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch runtimeFinished = new CountDownLatch(1);
        SessionTitleApplicationService titleService = new SessionTitleApplicationService(
                properties, appId -> Mono.just(false), request -> {
                    modelStarted.countDown();
                    return titleFails ? Mono.error(new IllegalStateException("model unavailable")) : titleResponse.asMono();
                }, new SessionTitleCommitService(sessions, metadata), metadata, sessions, messages, runs, titleIo);
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return runtimeRelease.asMono().thenMany(Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "answer")));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = defaultFinanceService(sessions, messages, runs, events, runtimeRouteService(), runtime);
        Object orchestrator = ReflectionTestUtils.getField(service, "orchestrator");
        Object execution = ReflectionTestUtils.getField(orchestrator, "runExecutionCoordinator");
        Object admission = ReflectionTestUtils.getField(execution, "admissionCoordinator");
        ReflectionTestUtils.setField(admission, "sessionTitleService", titleService);
        Object runtimeCoordinator = ReflectionTestUtils.getField(execution, "runtimeCoordinator");
        Object registry = ReflectionTestUtils.getField(runtimeCoordinator, "runExecutionRegistry");
        try {
            ChatRunStartResult started = service.startRun(new UserContext("tenant1", "user1", "User"),
                    new ChatCommand("command", null, null, "session_title", null, "web", "question", List.of(), Map.of()),
                    RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(3));
            assertThat(started).isNotNull();
            assertThat(modelStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(titleResponse.scan(reactor.core.Scannable.Attr.TERMINATED)).isFalse();
            runtimeRelease.tryEmitEmpty();
            // 等待后台Run本身结束，不依赖标题Publisher完成。
            LocalChatRunExecutionRegistry executions = (LocalChatRunExecutionRegistry) registry;
            for (int attempt = 0; attempt < 300; attempt++) {
                if (executions.activeClaims().stream().noneMatch(claim -> claim.runId().equals(started.runId()))) {
                    runtimeFinished.countDown();
                    break;
                }
                Thread.sleep(10);
            }
            assertThat(runtimeFinished.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(runs.runs.get(started.runId()).status()).isEqualTo(ChatRunStatus.COMPLETED);
            assertThat(events.events).extracting(ChatEvent::type)
                    .startsWith("run.started").contains("message.delta", "run.completed").doesNotContain("run.failed");
            assertThat(sessions.sessions.get("session_title").title()).isEqualTo("initial title");
        } finally {
            runtimeRelease.tryEmitEmpty();
            titleResponse.tryEmitEmpty();
            titleIo.dispose();
        }
    }
}
