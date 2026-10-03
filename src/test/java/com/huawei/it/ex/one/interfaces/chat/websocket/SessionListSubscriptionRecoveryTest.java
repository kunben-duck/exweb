/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces.chat.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.integration.conversation.ChatEventStore;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunCache;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.integration.conversation.ChatSessionLastRunSummary;
import com.huawei.it.ex.one.application.integration.conversation.SessionListFilter;
import com.huawei.it.ex.one.application.integration.conversation.SessionRepository;
import com.huawei.it.ex.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.application.service.chat.LocalChatEventStreamRegistry;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.ChatStreamTopics;
import com.huawei.it.ex.one.domain.chat.StoredChatEvent;
import com.huawei.it.ex.one.interfaces.chat.ChatEventTranslator;
import com.huawei.it.ex.one.interfaces.chat.ChatMessageVersionViewAssembler;
import com.huawei.it.ex.one.interfaces.chat.ChatSessionController;
import com.huawei.it.ex.one.interfaces.chat.ChatTurnStreamTranslator;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatSessionDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ConversationTurnStreamDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Sinks;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** 使用实际列表、恢复和WS协议层；仓储及跨实例总线为内存模拟，不依赖真实数据库或Redis。 */
class SessionListSubscriptionRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fourListedRunsRecoverWithoutDetailsIncludingCompletionBeforeSubscribe(boolean retainBusinessEvents)
            throws Exception {
        UserContext user = new UserContext("tenant1", "user1", "User One");
        ChatRunRepository runs = mock(ChatRunRepository.class);
        ChatEventStore store = mock(ChatEventStore.class);
        ChatLiveEventBus bus = mock(ChatLiveEventBus.class);
        ChatSessionFacade facade = mock(ChatSessionFacade.class);
        Map<String, ChatSessionLastRunSummary> summaries = new LinkedHashMap<>();
        Map<String, List<ChatEvent>> persisted = new LinkedHashMap<>();
        Map<String, Sinks.Many<ChatEvent>> live = new LinkedHashMap<>();
        List<ChatSession> sessions = new ArrayList<>();
        List<ChatRun> sourceRuns = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            String runId = "run_" + i;
            String sessionId = "session_" + i;
            ChatRun run = new ChatRun(runId, "tenant1", "user1", sessionId, ChatRunStatus.RUNNING,
                    "DOMAIN_AGENT", "skill1", "domain-agent", sessionId, (long) i * 100, null, null,
                    Instant.EPOCH, null, Map.of(), Instant.EPOCH, Instant.EPOCH);
            sourceRuns.add(run);
            sessions.add(new ChatSession(sessionId, "tenant1", "user1", "title", "ACTIVE", "web",
                    Instant.EPOCH, Instant.EPOCH));
            summaries.put(sessionId, new ChatSessionLastRunSummary(ChatRunStatus.RUNNING, null, runId));
            List<ChatEvent> history = new CopyOnWriteArrayList<>();
            history.add(event(run, i * 100L, "run.started"));
            if (retainBusinessEvents) {
                history.add(event(run, i * 100L + 1, "message.delta"));
            }
            if (i == 4) {
                history.add(event(run, i * 100L + 2, "run.async_running"));
            }
            persisted.put(runId, history);
            String topic = ChatStreamTopics.runTopic(runId);
            Sinks.Many<ChatEvent> sink = Sinks.many().multicast().onBackpressureBuffer();
            live.put(topic, sink);
            when(bus.subscribe(topic)).thenReturn(sink.asFlux());
            when(runs.findByTenantIdAndUserIdAndId("tenant1", "user1", runId))
                    .thenReturn(Optional.of(run));
            when(store.findByOwnerAndRunAfterSeq(eq("tenant1"), eq("user1"), eq(sessionId), eq(runId), anyLong()))
                    .thenAnswer(call -> history.stream()
                            .filter(value -> value.sequence() > call.<Long>getArgument(4)).toList());
        }
        doAnswer(call -> {
            live.get(call.<String>getArgument(0)).tryEmitNext(call.getArgument(1)).orThrow();
            return null;
        }).when(bus).publish(any(), any());
        when(runs.findLastRunBriefs("tenant1", "user1", sessions.stream().map(ChatSession::id).toList()))
                .thenReturn(summaries);
        when(facade.listSessions(user, SessionListFilter.empty(), null, 20))
                .thenReturn(new ChatSessionPage(sessions, null));
        when(facade.findFirstAssistantSummaries(user, sessions)).thenReturn(Map.of());
        ChatRunApplicationService runService = new ChatRunApplicationService(runs, mock(ChatRunCache.class), store,
                new PermissionChecker(), mock(SessionRepository.class), null, null, null, null);
        ChatSessionController controller = new ChatSessionController(facade,
                mock(ChatFeedbackApplicationService.class), runService, () -> user,
                new PermissionChecker(), new ChatMessageVersionViewAssembler());
        var page = controller.list(null, null, null, null, null, 20).block(Duration.ofSeconds(5));
        assertThat(page).isNotNull();
        assertThat(page.items()).extracting(ChatSessionDto::lastRunStatus).containsOnly("RUNNING");

        // 列表返回后、WS订阅前完成的Run只能通过持久化补发恢复，不能依赖实时发布。
        ChatRun completed = sourceRuns.getFirst();
        persisted.get(completed.id()).add(event(completed, 110, "run.completed"));
        when(runs.findByTenantIdAndUserIdAndId("tenant1", "user1", completed.id()))
                .thenReturn(Optional.of(completed.completed(110)));

        ChatStreamProperties properties = new ChatStreamProperties();
        ChatStreamApplicationService receivingInstance = streamService(store, bus, runs, properties);
        ChatStreamApplicationService publishingInstance = streamService(store, bus, runs, properties);
        LocalWebSocketConnectionRegistry registry = new LocalWebSocketConnectionRegistry();
        ChatWebSocketProtocolService protocol = new ChatWebSocketProtocolService(new PermissionChecker(),
                receivingInstance, registry, new ChatEventTranslator(), new ChatTurnStreamTranslator(),
                properties, new ObjectMapper());
        List<ChatWebSocketEnvelopeDto> outbound = new CopyOnWriteArrayList<>();
        protocol.open("connection1", user);
        try {
            for (ChatSessionDto item : page.items()) {
                protocol.handleTextMessage("connection1", user, outbound::add,
                        new ObjectMapper().writeValueAsString(Map.of("id", item.activeRunId(),
                                "type", "subscribe", "topicId", item.activeStreamTopicId(), "afterSeq", 0)))
                        .block(Duration.ofSeconds(5));
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(events(outbound, "run.started")).hasSize(4);
                assertThat(events(outbound, "run.completed")).hasSize(1);
                assertThat(events(outbound, "run.async_running")).hasSize(1);
                assertThat(registry.get("connection1").orElseThrow().subscriptionCount()).isEqualTo(3);
            });

            List<String> terminalTypes = List.of("run.cancelled", "run.failed", "run.completed");
            for (int i = 1; i < sourceRuns.size(); i++) {
                ChatRun run = sourceRuns.get(i);
                ChatEvent terminal = event(run, (i + 1) * 100L + 10, terminalTypes.get(i - 1));
                persisted.get(run.id()).add(terminal);
                // 独立实例的本地registry不共享，只通过模拟总线发送已提交终态。
                publishingInstance.publishPersisted(terminal);
            }
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(events(outbound, "run.completed")).hasSize(2);
                assertThat(events(outbound, "run.cancelled")).hasSize(1);
                assertThat(events(outbound, "run.failed")).hasSize(1);
                assertThat(registry.get("connection1").orElseThrow().subscriptionCount()).isZero();
            });
            assertThat(outbound).noneMatch(envelope -> "error".equals(envelope.type()));
            for (ChatWebSocketEnvelopeDto envelope : events(outbound, "run.started")) {
                ConversationTurnStreamDto turn = envelope.payload();
                assertThat(envelope.topicId()).isEqualTo(ChatStreamTopics.runTopic(turn.payload().turnId()));
                assertThat(envelope.offset()).isEqualTo(String.valueOf(turn.payload().encodedItem().data().sequence()));
            }
            verify(facade, never()).getSession(any(), any());
            verify(facade, never()).markSessionRead(any(), any(), anyLong());
            verify(runs, never()).findActiveBySession(any(), any(), any());
            verify(runs, never()).findLastRunSummaries(any(), any(), any());
            verify(store, never()).findLatestSeqByOwnerAndSession(any(), any(), any());
        } finally {
            protocol.close("connection1", user);
        }
    }

    private ChatStreamApplicationService streamService(ChatEventStore store, ChatLiveEventBus bus,
                                                        ChatRunRepository runs, ChatStreamProperties properties) {
        return new ChatStreamApplicationService(store, new LocalChatEventStreamRegistry(), bus, runs,
                new PermissionChecker(), mock(SessionRepository.class), new ChatWebSocketProperties(), properties);
    }

    private ChatEvent event(ChatRun run, long sequence, String type) {
        return new StoredChatEvent(run.id(), run.sessionId(), sequence, type, Instant.EPOCH, Map.of());
    }

    private List<ChatWebSocketEnvelopeDto> events(List<ChatWebSocketEnvelopeDto> outbound, String type) {
        return outbound.stream().filter(envelope -> envelope.payload() != null
                && envelope.payload().payload().encodedItem() != null
                && type.equals(envelope.payload().payload().encodedItem().event())).toList();
    }
}
