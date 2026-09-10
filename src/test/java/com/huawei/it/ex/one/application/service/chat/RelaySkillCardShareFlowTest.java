/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatShareSelectedMessagesProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.share.ChatShareRepository;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.application.service.share.ChatShareApplicationService;
import com.huawei.it.ex.one.application.service.share.CreateChatShareCommand;
import com.huawei.it.ex.one.application.service.share.CreateSelectedChatShareCommand;
import com.huawei.it.ex.one.application.service.share.SelectedChatShareApplicationService;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.infrastructure.runtime.relay.RelayRuntimeResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.runtime.relay.RelaySkillCardTestFrames;
import com.huawei.it.ex.one.infrastructure.share.DefaultChatShareAccessPolicy;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

class RelaySkillCardShareFlowTest extends ChatFlowTestSupport {
    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final RelayRuntimeResponseNormalizer normalizer = new RelayRuntimeResponseNormalizer(mapper);
    private final List<ChatEvent> published = new CopyOnWriteArrayList<>();

    @Test
    void skillCardsReachLivePublicationHistoryAndResumeWithoutWaitingForUser() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        AgentRuntime runtime = new AgentRuntime() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.fromIterable(skillCards(request.runId(), request.sessionId()))
                        .concatWith(Flux.fromIterable(normalizer.normalize(
                                request.runId(), request.sessionId(), "[DONE]")));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithTerminalCommit(
                sessions, messages, runs, events, runtimeRouteService(), runtime);
        List<ChatEvent> output = service.executeRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "web", "生成案例", List.of(), Map.of()))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(output).isNotNull();
        assertThat(output).extracting(ChatEvent::type)
                .containsSubsequence("runtime.card", "runtime.card", "message.completed", "run.completed")
                .doesNotContain("run.waiting_user", "run.failed", "message.delta", "message.snapshot");
        List<ChatEvent> cards = output.stream().filter(event -> "runtime.card".equals(event.type())).toList();
        assertThat(cards).hasSize(2);
        assertThat(published.stream().filter(event -> "runtime.card".equals(event.type())).toList())
                .containsExactlyElementsOf(cards);
        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role())).findFirst().orElseThrow();
        assertThat(assistant.content()).isEmpty();
        assertThat(assistant.parts()).extracting(ChatMessagePart::partType).containsExactly("CARD", "CARD", "ANSWER");
        assertThat(runs.findById(assistant.runId())).get()
                .extracting(run -> run.status()).isEqualTo(ChatRunStatus.COMPLETED);

        ChatStreamApplicationService streams = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                new PermissionChecker(), sessions, new ChatWebSocketProperties());
        List<ChatEvent> resumed = streams.resumeRun(user, assistant.runId(), 0L)
                .filter(event -> "runtime.card".equals(event.type()))
                .collectList().block(Duration.ofSeconds(5));
        assertThat(resumed).containsExactlyElementsOf(cards);
    }

    @Test
    void assembledCardsAreVisibleAndIncludedInBothShareDetailFormats() {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        InMemoryMessageRepository messages = new InMemoryMessageRepository() {
            @Override
            public List<ChatMessagePart> findPartsByMessageIds(
                    String tenantId, String userId, String sessionId, List<String> messageIds) {
                return parts.stream().filter(part -> tenantId.equals(part.tenantId())
                        && userId.equals(part.userId()) && sessionId.equals(part.sessionId())
                        && messageIds.contains(part.messageId())).toList();
            }
        };
        SequentialIdGenerator ids = new SequentialIdGenerator();
        PermissionChecker permissions = new PermissionChecker();
        SessionApplicationService sessionService = new SessionApplicationService(sessions, messages, ids, permissions);
        var session = sessionService.createSession(user, "技能卡片", "web");
        ChatMessage question = messages.save(new ChatMessage("user-message", "tenant1", "user1", session.id(),
                null, 1L, 0, 1, "user", "生成案例", null, "run1", "NORMAL", false,
                null, null, null, null, null, Instant.now()));
        AssistantAssembly assembly = new AssistantAssembly();
        skillCards("run1", session.id()).forEach(assembly::observe);
        normalizer.normalize("run1", session.id(), "{\"type\":\"future-debug-event\"}")
                .forEach(assembly::observe);
        ChatMessage assistant = sessionService.saveAssistantMessage(new AssistantMessageSaveCommand(
                "tenant1", "user1", session, assembly.finalContent(), "run1", question.id(), null,
                assembly.parts(), null));

        List<ChatMessagePart> cards = assistant.parts().stream()
                .filter(part -> "CARD".equals(part.partType())).toList();
        assertThat(cards).hasSize(2).allSatisfy(part -> {
            assertThat(part.visible()).isTrue();
            assertThat(part.channel()).isEqualTo("card");
            assertThat(part.displayHint()).isEqualTo("inline");
            assertThat(part.sourceType()).isEqualTo("skill-card-broadcast");
        });
        assertThat(cards).extracting(part -> part.payload().get("cardType")).containsExactly("url", "diyCardScene");
        assertThat(assistant.content()).isEmpty();
        Map<String, ChatShare> savedShares = new HashMap<>();
        ChatShareRepository shares = mock(ChatShareRepository.class);
        when(shares.save(any())).thenAnswer(invocation -> {
            ChatShare share = invocation.getArgument(0);
            savedShares.put(share.id(), share);
            return share;
        });
        when(shares.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(
                savedShares.get(invocation.getArgument(0))));
        DefaultChatShareAccessPolicy policy = new DefaultChatShareAccessPolicy();
        ChatShareApplicationService single = new ChatShareApplicationService(
                shares, messages, sessions, ids, permissions, policy);
        SelectedChatShareApplicationService selected = new SelectedChatShareApplicationService(
                shares, messages, sessions, ids, permissions, policy,
                new ChatShareSelectedMessagesProperties(), mapper);
        ChatShare singleShare = single.create(user, new CreateChatShareCommand(assistant.id(), null, null));
        ChatShare selectedShare = selected.create(user, new CreateSelectedChatShareCommand(
                session.id(), List.of(question.id(), assistant.id()), null, null));

        var singleParts = single.get(user, singleShare.id()).snapshot().parts();
        var selectedParts = single.get(user, selectedShare.id()).snapshot().messages().getLast().parts();
        assertThat(singleParts).extracting(part -> part.payload())
                .containsExactlyElementsOf(cards.stream().map(ChatMessagePart::payload).toList());
        assertThat(selectedParts).containsExactlyElementsOf(singleParts);
        assertThat(singleParts).extracting(part -> part.partOrder())
                .containsExactlyElementsOf(cards.stream().map(ChatMessagePart::partOrder).toList());
    }

    @Test
    void noStoreDoesNotPromoteSkillCardsToPersistedControlFacts() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("结果不留存")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);
        AgentDataPersistenceEventPolicy policy = new AgentDataPersistenceEventPolicy();
        skillCards("run1", "session1").forEach(event -> {
            assembly.observe(event);
            assertThat(policy.retention(event, state))
                    .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.LIVE_ONLY);
        });
        assertThat(assembly.parts()).isEmpty();
        assertThat(assembly.finalContent()).isEqualTo("结果不留存");
    }

    private List<ChatEvent> skillCards(String runId, String sessionId) {
        return Stream.of(RelaySkillCardTestFrames.URL_CARD, RelaySkillCardTestFrames.SCENE_CARD)
                .flatMap(frame -> normalizer.normalize(runId, sessionId, frame).stream()).toList();
    }

    @Override
    ChatLiveEventBus liveEventBus() {
        return new ChatLiveEventBus() {
            @Override
            public void publish(String topicId, ChatEvent event) {
                published.add(event);
            }

            @Override
            public Flux<ChatEvent> subscribe(String topicId) {
                return Flux.never();
            }
        };
    }
}
