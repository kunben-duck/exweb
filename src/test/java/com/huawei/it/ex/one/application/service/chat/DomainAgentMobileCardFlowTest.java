/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.ChatShareSelectedMessagesProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
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
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.share.DefaultChatShareAccessPolicy;
import com.huawei.it.ex.one.interfaces.chat.ChatEventTranslator;
import com.huawei.it.ex.one.interfaces.chat.ChatTurnStreamTranslator;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

class DomainAgentMobileCardFlowTest extends ChatFlowTestSupport {
    private static final String CARD = """
            {"cardUrl":"https://cards.test/web.js","mobileCardUrl":"https://cards.test/mobile.js"}
            """;
    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(mapper);
    private final List<ChatEvent> published = new CopyOnWriteArrayList<>();

    @Test
    void mobileAddressSurvivesLiveSerializationHistoryResumeAndBothShareFormats() {
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
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        DomainAgentClient client = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(), CARD))
                        .concatWith(Flux.fromIterable(normalizer.normalize(
                                request.runId(), request.sessionId(), "{\"endFlag\":true}")));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        AgentRuntime relay = mock(AgentRuntime.class);
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, repeatedDomainAgentRouteService(new AtomicInteger(), "skill1"),
                client, relay, runtimeBindingRepository(), new DomainAgentProperties());
        List<ChatEvent> output = service.executeRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "mobile", "Show card", List.of(), Map.of()))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(output).isNotNull();
        assertThat(output).extracting(ChatEvent::type)
                .containsSubsequence("runtime.card", "message.completed", "run.completed")
                .doesNotContain("message.delta", "message.snapshot", "run.waiting_user", "run.failed");
        List<ChatEvent> cards = output.stream().filter(event -> "runtime.card".equals(event.type())).toList();
        assertThat(cards).singleElement().satisfies(event -> assertThat(event.payload())
                .containsEntry("cardUrl", "https://cards.test/web.js")
                .containsEntry("mobileCardUrl", "https://cards.test/mobile.js"));
        assertThat(published.stream().filter(event -> "runtime.card".equals(event.type())).toList())
                .containsExactlyElementsOf(cards);
        var wire = mapper.valueToTree(new ChatTurnStreamTranslator().streamItem(
                new ChatEventTranslator().toDto(cards.getFirst())));
        assertThat(wire.at("/payload/encodedItem/data/payload/mobileCardUrl").asText())
                .isEqualTo("https://cards.test/mobile.js");
        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role())).findFirst().orElseThrow();
        assertThat(assistant.content()).isEmpty();
        ChatMessagePart card = assistant.parts().stream()
                .filter(part -> "CARD".equals(part.partType())).findFirst().orElseThrow();
        assertThat(card.visible()).isTrue();
        assertThat(card.payload()).containsAllEntriesOf(cards.getFirst().payload());
        ChatStreamApplicationService streams = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                new PermissionChecker(), sessions, new ChatWebSocketProperties());
        assertThat(streams.resumeRun(user, assistant.runId(), 0L)
                .filter(event -> "runtime.card".equals(event.type())).collectList().block(Duration.ofSeconds(5)))
                .containsExactlyElementsOf(cards);
        assertSharePayloads(sessions, messages, assistant, card);
        verify(relay, never()).query(any());
    }

    private void assertSharePayloads(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                     ChatMessage assistant, ChatMessagePart card) {
        Map<String, ChatShare> savedShares = new HashMap<>();
        ChatShareRepository shares = mock(ChatShareRepository.class);
        when(shares.save(any())).thenAnswer(invocation -> {
            ChatShare share = invocation.getArgument(0);
            savedShares.put(share.id(), share);
            return share;
        });
        when(shares.findById(anyString())).thenAnswer(invocation -> Optional.ofNullable(
                savedShares.get(invocation.getArgument(0))));
        SequentialIdGenerator ids = new SequentialIdGenerator();
        PermissionChecker permissions = new PermissionChecker();
        DefaultChatShareAccessPolicy policy = new DefaultChatShareAccessPolicy();
        ChatShareApplicationService single = new ChatShareApplicationService(
                shares, messages, sessions, ids, permissions, policy);
        SelectedChatShareApplicationService selected = new SelectedChatShareApplicationService(
                shares, messages, sessions, ids, permissions, policy,
                new ChatShareSelectedMessagesProperties(), mapper);
        ChatShare singleShare = single.create(user, new CreateChatShareCommand(assistant.id(), null, null));
        ChatShare selectedShare = selected.create(user, new CreateSelectedChatShareCommand(
                assistant.sessionId(), List.of(assistant.parentMessageId(), assistant.id()), null, null));
        assertThat(single.get(user, singleShare.id()).snapshot().parts())
                .filteredOn(part -> "CARD".equals(part.partType())).singleElement()
                .satisfies(part -> assertThat(part.payload()).isEqualTo(card.payload()));
        assertThat(single.get(user, selectedShare.id()).snapshot().messages().getLast().parts())
                .filteredOn(part -> "CARD".equals(part.partType())).singleElement()
                .satisfies(part -> assertThat(part.payload()).isEqualTo(card.payload()));
    }

    @Test
    void noStoreCardRemainsLiveOnlyWithoutSavingEitherAddress() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("not stored")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);
        ChatEvent card = normalizer.normalize("run1", "session1", CARD).getFirst();
        assembly.observe(card);
        assertThat(card.payload()).containsEntry("mobileCardUrl", "https://cards.test/mobile.js");
        assertThat(new AgentDataPersistenceEventPolicy().retention(card, state))
                .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.LIVE_ONLY);
        assertThat(assembly.parts()).isEmpty();
        assertThat(assembly.finalContent()).isEqualTo("not stored");
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
