/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import com.huawei.it.ex.one.application.config.RunAdmissionProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunStartedEvent;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

class ChatRunUserMessageIdFlowTest extends ChatFlowTestSupport {
    @ParameterizedTest
    @CsvSource({"NEXT,false", "NEXT,true", "EDIT_USER,false", "REGENERATE_ASSISTANT,false"})
    void startReturnsTrustedUserMessageBeforeRuntimeOutput(ChatRunMode mode, boolean attachmentOnly) {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        UserContext user = new UserContext("tenant1", "user1", "User One");
        Instant now = Instant.now();
        sessions.save(new ChatSession("session1", "tenant1", "user1", "原会话", "ACTIVE", "web",
                "original-user", "original-assistant", null, null, 2L, null, now, now));
        messages.save(new ChatMessage("original-user", "tenant1", "user1", "session1",
                null, 1L, 0, 1, "user", "原问题", null, "original-run",
                "NORMAL", false, null, null, null, null, null, now));
        messages.save(new ChatMessage("original-assistant", "tenant1", "user1", "session1",
                "original-user", 2L, 1, 1, "assistant", "原答案", null, "original-run",
                "NORMAL", false, null, null, null, null, null, now));
        Sinks.Empty<Void> release = Sinks.empty();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return release.asMono().thenMany(Flux.just(
                        MessageDeltaEvent.of(request.runId(), request.sessionId(), "新答案")));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), runtime);
        ChatCommand command = new ChatCommand("cmd1", null, null, "session1", null, "web",
                attachmentOnly || mode == ChatRunMode.REGENERATE_ASSISTANT ? null : "新问题",
                attachmentOnly ? List.of(new AttachmentRef("doc1", null, null, null)) : List.of(),
                Map.of("userMessageId", "forged"), null, null, mode, null,
                mode == ChatRunMode.EDIT_USER ? "original-user" : null,
                mode == ChatRunMode.REGENERATE_ASSISTANT ? "original-assistant" : null);

        try {
            ChatRunStartResult result = service.startRun(user, command, RuntimeForwardHeaders.empty())
                    .block(Duration.ofSeconds(5));

            assertThat(result).isNotNull();
            ChatRun run = runs.findById(result.runId()).orElseThrow();
            assertThat(run.status()).isEqualTo(ChatRunStatus.RUNNING);
            assertThat(result.userMessageId()).isNotBlank().isNotEqualTo("forged")
                    .isEqualTo(run.userMessageId());
            assertThat(events.events).singleElement().satisfies(event -> {
                assertThat(event.type()).isEqualTo("run.started");
                assertThat(event.sequence()).isEqualTo(result.firstSeq());
                assertThat(event.payload()).containsExactlyInAnyOrderEntriesOf(
                        Map.of("status", "STARTED", "userMessageId", result.userMessageId()));
            });
            assertThat(messages.messages).filteredOn(message -> result.userMessageId().equals(message.id()))
                    .singleElement().satisfies(message -> {
                        assertThat(message.role()).isEqualTo("user");
                        if (mode == ChatRunMode.REGENERATE_ASSISTANT) {
                            assertThat(message.id()).isEqualTo("original-user");
                            assertThat(message.runId()).isEqualTo("original-run");
                        } else {
                            assertThat(message.id()).isNotEqualTo("original-user");
                            assertThat(message.runId()).isEqualTo(result.runId());
                        }
                    });
        } finally {
            release.tryEmitEmpty();
        }
        awaitEvent(events, "run.completed");
    }

    @Test
    void failedFirstEventStillReturnsAdmittedUserWithoutChangingFailure() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), noopRuntime(),
                new FailingExecutionRepository());

        ChatRunStartResult result = service.startRun(new UserContext("tenant1", "user1", "User One"),
                new ChatCommand("cmd1", null, null, null, null, "web", "问题", List.of(), Map.of()),
                RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.userMessageId()).isNotBlank()
                .isEqualTo(runs.findById(result.runId()).orElseThrow().userMessageId());
        assertThat(events.events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("run.failed");
            assertThat(event.payload()).containsEntry("code", "RUN_EXECUTION_INIT_FAILED");
        });
    }

    @Test
    void legacyStartWithoutRecordedRunKeepsNullableAssociation() {
        ChatRunStartCoordinator coordinator = new ChatRunStartCoordinator(new FixedIdGenerator(),
                new RunAdmissionControlService(new RunAdmissionProperties()), new LocalChatRunExecutionRegistry(),
                new ChatRunOperationalProperties(), null);

        ChatRunStartResult result = coordinator.startStandard(new UserContext("tenant1", "user1", "User One"),
                TraceContext.empty(),
                new ChatCommand("cmd1", null, null, null, null, "web", "问题", List.of(), Map.of()),
                attempt -> Flux.just(RunStartedEvent.of(attempt.runId(), "session1")))
                .block(Duration.ofSeconds(5));

        assertThat(result).isNotNull();
        assertThat(result.userMessageId()).isNull();
        assertThat(RunStartedEvent.of(result.runId(), "session1").payload())
                .containsExactlyInAnyOrderEntriesOf(Map.of("status", "STARTED"));
    }
}
