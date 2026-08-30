/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.chat.SequencedChatEvent;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

class ChatEventPipelineRetentionTest {
    private static final String RUN_ID = "run1";
    private static final String SESSION_ID = "session1";
    private static final RunExecutionClaim CLAIM =
            new RunExecutionClaim(RUN_ID, "instance1", 3L);

    @Test
    void placeholderBusinessEventGetsSequenceAndLivePublicationWithoutPersistence() {
        Fixture fixture = new Fixture();
        ChatEvent input = MessageDeltaEvent.of(RUN_ID, SESSION_ID, "真实回答");
        ChatEvent sequenced = sequenced(input, 101L);
        when(fixture.streamService.sequenceLiveBatchWithExecutionGuard(anyList(), eq(CLAIM)))
                .thenReturn(List.of(sequenced));
        AtomicBoolean persisted = new AtomicBoolean();

        List<ChatEvent> output = fixture.pipeline.persistAndPublish(
                        Flux.just(input),
                        fixture.context(true),
                        event -> {
                            persisted.set(true);
                            return Mono.just(event);
                        })
                .collectList()
                .block();

        assertThat(output).containsExactly(sequenced);
        assertThat(persisted).isFalse();
        verify(fixture.streamService).publishLiveOnly(sequenced);
        verify(fixture.streamService, never()).publishPersisted(any());
        assertThat(fixture.assistant.shouldPersistMessage()).isTrue();
        assertThat(fixture.assistant.finalContent()).isEqualTo("回答已隐藏");
    }

    @Test
    void placeholderIntentAndInteractionControlsStillUsePersistentWriter() {
        Fixture fixture = new Fixture();
        List<ChatEvent> inputs = List.of(
                RuntimeEvent.progress(RUN_ID, SESSION_ID, Map.of(
                        "source", "intent-agent",
                        "sourceType", "intent-progress")),
                RuntimeEvent.thinking(RUN_ID, SESSION_ID, Map.of(
                        "source", "intent-agent",
                        "sourceType", "intent-delta")),
                RuntimeEvent.card(RUN_ID, SESSION_ID, Map.of(
                        "source", "relay",
                        "sourceType", "approval-request",
                        "operation_type", "questionnaire",
                        "approval_id", "approval1",
                        "questions", List.of(Map.of("question", "请选择技能")))),
                RuntimeEvent.metadata(RUN_ID, SESSION_ID, Map.of(
                        "source", "domain-agent",
                        "sourceType", "agent.refusal",
                        "metadataType", "domain_agent_control",
                        "type", "agent.refusal",
                        "code", "FN-EX-CAHT-BIZ-DAG-001",
                        "supervisorAction", "REROUTE")));
        AtomicLong sequence = new AtomicLong(10L);

        List<ChatEvent> output = fixture.pipeline.persistAndPublish(
                        Flux.fromIterable(inputs),
                        fixture.context(true),
                        event -> Mono.just(stored(event, sequence.getAndIncrement())))
                .collectList()
                .block();

        assertThat(output).hasSize(4);
        verify(fixture.streamService, never())
                .sequenceLiveBatchWithExecutionGuard(anyList(), any());
        verify(fixture.streamService, never()).publishLiveOnly(any());
    }

    @Test
    void fullPolicyKeepsBusinessEventOnExistingPersistentPath() {
        Fixture fixture = new Fixture();
        ChatEvent input = MessageDeltaEvent.of(RUN_ID, SESSION_ID, "完整回答");
        AtomicBoolean persisted = new AtomicBoolean();

        fixture.pipeline.persistAndPublish(
                        Flux.just(input),
                        fixture.context(false),
                        event -> {
                            persisted.set(true);
                            return Mono.just(stored(event, 11L));
                        })
                .collectList()
                .block();

        assertThat(persisted).isTrue();
        verify(fixture.streamService, never())
                .sequenceLiveBatchWithExecutionGuard(anyList(), any());
    }

    @Test
    void unknownDownstreamRuntimeEventDefaultsToLiveOnly() {
        Fixture fixture = new Fixture();
        ChatEvent input = RuntimeEvent.metadata(RUN_ID, SESSION_ID, Map.of(
                "source", "domain-agent",
                "sourceType", "future-business-output",
                "interactionId", "untrusted-downstream-value",
                "content", "真实业务内容"));
        ChatEvent sequenced = sequenced(input, 102L);
        when(fixture.streamService.sequenceLiveBatchWithExecutionGuard(anyList(), eq(CLAIM)))
                .thenReturn(List.of(sequenced));

        List<ChatEvent> output = fixture.pipeline.persistAndPublish(
                        Flux.just(input),
                        fixture.context(true),
                        event -> Mono.error(new AssertionError("Unexpected event persistence")))
                .collectList()
                .block();

        assertThat(output).containsExactly(sequenced);
        verify(fixture.streamService).publishLiveOnly(sequenced);
    }

    @Test
    void controlLikeDownstreamBusinessEventsRemainLiveOnly() {
        Fixture fixture = new Fixture();
        List<ChatEvent> inputs = List.of(
                RuntimeEvent.progress(RUN_ID, SESSION_ID, Map.of(
                        "source", "relay",
                        "sourceType", "approval-result",
                        "content", "真实业务结果")),
                RuntimeEvent.metadata(RUN_ID, SESSION_ID, Map.of(
                        "source", "domain-agent",
                        "sourceType", "clarification-analysis",
                        "interactionId", "untrusted-value",
                        "content", "真实分析内容")));
        AtomicLong sequence = new AtomicLong(201L);
        when(fixture.streamService.sequenceLiveBatchWithExecutionGuard(anyList(), eq(CLAIM)))
                .thenAnswer(invocation -> invocation.<List<ChatEvent>>getArgument(0).stream()
                        .map(event -> sequenced(event, sequence.getAndIncrement()))
                        .toList());

        List<ChatEvent> output = fixture.pipeline.persistAndPublish(
                        Flux.fromIterable(inputs),
                        fixture.context(true),
                        event -> Mono.error(new AssertionError("Unexpected event persistence")))
                .collectList()
                .block();

        assertThat(output).hasSize(2);
        verify(fixture.streamService, times(2)).publishLiveOnly(any());
    }

    @Test
    void liveRuntimeSessionIdOnlyReadsRunWhenBindingSessionChanges() {
        Fixture fixture = new Fixture();
        RuntimeBinding initialBinding = mock(RuntimeBinding.class);
        RuntimeBinding establishedBinding = mock(RuntimeBinding.class);
        RuntimeBinding switchedBinding = mock(RuntimeBinding.class);
        when(initialBinding.runtimeSessionId()).thenReturn(null);
        when(establishedBinding.runtimeSessionId()).thenReturn("runtime-session-1");
        when(switchedBinding.runtimeSessionId()).thenReturn("runtime-session-2");
        when(fixture.bindingService.observeEvent(eq(initialBinding), any()))
                .thenReturn(establishedBinding);
        when(fixture.bindingService.observeEvent(eq(establishedBinding), any()))
                .thenReturn(establishedBinding, establishedBinding, switchedBinding);
        List<ChatEvent> inputs = List.of(
                liveRuntimeEvent("relay-progress", "runtime-session-1"),
                liveRuntimeEvent("message-delta", "runtime-session-1"),
                liveRuntimeEvent("thinking-content-update", "runtime-session-1"),
                liveRuntimeEvent("session-ready", "runtime-session-2"));
        AtomicLong sequence = new AtomicLong(301L);
        when(fixture.streamService.sequenceLiveBatchWithExecutionGuard(anyList(), eq(CLAIM)))
                .thenAnswer(invocation -> invocation.<List<ChatEvent>>getArgument(0).stream()
                        .map(event -> sequenced(event, sequence.getAndIncrement()))
                        .toList());

        fixture.pipeline.persistAndPublish(
                        Flux.fromIterable(inputs),
                        fixture.context(true, initialBinding),
                        event -> Mono.error(new AssertionError("Unexpected event persistence")))
                .collectList()
                .block();

        verify(fixture.runService, times(2)).observeLiveOnlyRuntimeState(any());
        verify(fixture.bindingService, times(4)).observeEvent(any(), any());
    }

    private static ChatEvent liveRuntimeEvent(String sourceType, String runtimeSessionId) {
        return RuntimeEvent.progress(RUN_ID, SESSION_ID, Map.of(
                "source", "relay",
                "sourceType", sourceType,
                "runtimeSessionId", runtimeSessionId));
    }

    private static ChatEvent sequenced(ChatEvent source, long sequence) {
        return new SequencedChatEvent(
                source.runId(), source.sessionId(), sequence, source.type(),
                source.createdAt(), source.payload());
    }

    private static ChatEvent stored(ChatEvent source, long sequence) {
        return new StoredChatEvent(
                source.runId(), source.sessionId(), sequence, source.type(),
                source.createdAt(), source.payload());
    }

    private static final class Fixture {
        private final ChatRunApplicationService runService = mock(ChatRunApplicationService.class);
        private final ChatStreamApplicationService streamService = mock(ChatStreamApplicationService.class);
        private final RuntimeBindingApplicationService bindingService =
                mock(RuntimeBindingApplicationService.class);
        private final AssistantAssembly assistant = new AssistantAssembly(
                new AgentDataPersistenceState("回答已隐藏")
                        .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER));
        private final ChatEventPipeline pipeline;

        private Fixture() {
            when(runService.shouldAcceptEvent(any())).thenReturn(true);
            pipeline = new ChatEventPipeline(
                    new ChatDeltaCoalescer(new ChatStreamProperties()),
                    Schedulers.immediate(),
                    null,
                    runService,
                    streamService,
                    bindingService,
                    new ChatRunCompletionCoordinator(null, null, null, null, null, null, null));
        }

        private RunEventPipelineContext context(boolean placeholder) {
            return context(placeholder, null);
        }

        private RunEventPipelineContext context(boolean placeholder, RuntimeBinding binding) {
            AssistantAssembly selectedAssistant = placeholder
                    ? assistant
                    : new AssistantAssembly();
            ChatSession session = new ChatSession(
                    SESSION_ID, "tenant1", "user1", "title", "ACTIVE", "web",
                    Instant.EPOCH, Instant.EPOCH);
            return new RunEventPipelineContext(
                    null,
                    session,
                    null,
                    new AtomicReference<>(),
                    new AtomicReference<>(binding),
                    selectedAssistant,
                    RUN_ID,
                    CLAIM,
                    new AtomicReference<>(),
                    null,
                    null,
                    List.of());
        }
    }
}
