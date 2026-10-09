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
import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.conversation.ChatLiveEventBus;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
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
import com.huawei.it.ex.one.domain.chat.ChatMessagePage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatShare;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.share.DefaultChatShareAccessPolicy;
import com.huawei.it.ex.one.interfaces.chat.ChatEventTranslator;
import com.huawei.it.ex.one.interfaces.chat.ChatMessageVersionViewAssembler;
import com.huawei.it.ex.one.interfaces.chat.ChatSessionController;
import com.huawei.it.ex.one.interfaces.chat.ChatTurnStreamTranslator;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;

import com.fasterxml.jackson.databind.JsonNode;
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

class DomainAgentThinkToolFlowTest extends ChatFlowTestSupport {
    private static final String PROCESS = """
            data: {"type":"think","thinkState":"start","thinkTitle":"正在分析","thinkContent":"我理解"}

            data: {"type":"think","thinkContent":"您希望分析数据"}

            data: {"type":"tool","toolTitle":"glob","toolMatchRes":"[]"}

            data: {"type":"think","thinkState":"stop","thinkTitle":"分析完成","thinkTime":"2026-10-08T02:41:49Z"}

            """;
    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(mapper);
    private final List<ChatEvent> published = new CopyOnWriteArrayList<>();

    @Test
    void rawFieldsSurviveLiveHistoryResumeAndBothShareFormats() {
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
                return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(),
                        "data: {\"content\":\"before\"}\n\n" + PROCESS
                                + "data: {\"content\":\"after\",\"endFlag\":true}\n\n"));
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
                        "cmd1", null, null, null, null, "web", "Analyze", List.of(), Map.of()))
                .collectList().block(Duration.ofSeconds(10));
        assertThat(output).isNotNull();
        assertThat(output).extracting(ChatEvent::type).containsSubsequence("message.delta", "runtime.thinking",
                "runtime.thinking", "runtime.tool", "runtime.thinking", "message.delta",
                "message.completed", "run.completed").doesNotContain("run.waiting_user", "run.failed");
        List<ChatEvent> process = output.stream().filter(DomainAgentThinkToolFlowTest::processEvent).toList();
        assertThat(process).hasSize(4);
        assertThat(published.stream().filter(DomainAgentThinkToolFlowTest::processEvent).toList())
                .containsExactlyElementsOf(process);
        for (ChatEvent event : process) {
            var turn = new ChatTurnStreamTranslator().streamItem(new ChatEventTranslator().toDto(event));
            JsonNode wire = mapper.valueToTree(ChatWebSocketEnvelopeDto.message(
                    "chat-run-" + event.runId(), turn, String.valueOf(event.sequence())));
            assertThat(wire.at("/payload/payload/encodedItem/data/payload"))
                    .isEqualTo(mapper.valueToTree(event.payload()));
            assertThat(wire.get("offset").asText()).isEqualTo(String.valueOf(event.sequence()));
            assertThat(event.payload()).doesNotContainKeys("status", "title", "text", "toolName");
        }
        ChatMessage assistant = messages.messages.stream()
                .filter(message -> "assistant".equals(message.role())).findFirst().orElseThrow();
        assertThat(assistant.content()).isEqualTo("before" + AssistantAssembly.DOMAIN_AGENT_CONTENT_SEGMENT_MARKER + "after");
        List<ChatMessagePart> parts = assistant.parts().stream()
                .filter(part -> "THINKING".equals(part.partType()) || "TOOL".equals(part.partType())).toList();
        assertThat(parts).extracting(ChatMessagePart::status).containsExactly("STARTED", "STREAMING", "STREAMING", "COMPLETED");
        assertThat(parts).extracting(ChatMessagePart::title).containsExactly("正在分析", "思考过程", "工具调用", "分析完成");
        assertThat(parts).extracting(ChatMessagePart::contentText).containsExactly("我理解", "您希望分析数据", "glob", "分析完成");
        for (int index = 0; index < parts.size(); index++) {
            ChatMessagePart part = parts.get(index);
            ChatEvent event = process.get(index);
            assertThat(part.visible()).isTrue();
            assertThat(part.displayHint()).isEqualTo("collapsible");
            assertThat(part.payload()).hasSize(event.payload().size() + 1).containsAllEntriesOf(event.payload())
                    .containsEntry("serverTimestampMs", event.createdAt().toEpochMilli());
        }
        ChatStreamApplicationService streams = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                new PermissionChecker(), sessions, new ChatWebSocketProperties());
        assertThat(streams.resumeRun(user, assistant.runId(), 0L).filter(DomainAgentThinkToolFlowTest::processEvent)
                .collectList().block(Duration.ofSeconds(5))).containsExactlyElementsOf(process);
        assertHistoryResponse(assistant, parts);
        assertSharePayloads(sessions, messages, assistant, parts);
        verify(relay, never()).query(any());
    }

    private void assertHistoryResponse(ChatMessage assistant, List<ChatMessagePart> parts) {
        ChatSessionFacade facade = mock(ChatSessionFacade.class);
        AuthContextProvider auth = mock(AuthContextProvider.class);
        when(auth.resolve()).thenReturn(user);
        when(facade.listMessages(user, assistant.sessionId(), null, null, 50))
                .thenReturn(new ChatMessagePage(List.of(assistant), null));
        ChatSessionController controller = new ChatSessionController(facade,
                mock(ChatFeedbackApplicationService.class), mock(ChatRunApplicationService.class),
                auth, new PermissionChecker(), new ChatMessageVersionViewAssembler());
        var page = controller.messages(assistant.sessionId(), null, null, 50).block(Duration.ofSeconds(5));
        assertThat(page).isNotNull();
        var history = page.items().getFirst().parts().stream()
                .filter(part -> "THINKING".equals(part.partType()) || "TOOL".equals(part.partType())).toList();
        assertThat(history).hasSameSizeAs(parts);
        for (int index = 0; index < parts.size(); index++) {
            assertThat(history.get(index).payload()).isEqualTo(parts.get(index).payload());
            assertThat(history.get(index).title()).isEqualTo(parts.get(index).title());
            assertThat(history.get(index).status()).isEqualTo(parts.get(index).status());
            assertThat(history.get(index).contentText()).isEqualTo(parts.get(index).contentText());
        }
    }

    private void assertSharePayloads(InMemorySessionRepository sessions, InMemoryMessageRepository messages,
                                     ChatMessage assistant, List<ChatMessagePart> parts) {
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
        ChatShareApplicationService single = new ChatShareApplicationService(shares, messages, sessions, ids, permissions, policy);
        SelectedChatShareApplicationService selected = new SelectedChatShareApplicationService(
                shares, messages, sessions, ids, permissions, policy, new ChatShareSelectedMessagesProperties(), mapper);
        ChatShare singleShare = single.create(user, new CreateChatShareCommand(assistant.id(), null, null));
        ChatShare selectedShare = selected.create(user, new CreateSelectedChatShareCommand(
                assistant.sessionId(), List.of(assistant.parentMessageId(), assistant.id()), null, null));
        assertThat(single.get(user, singleShare.id()).snapshot().parts())
                .filteredOn(part -> "THINKING".equals(part.partType()) || "TOOL".equals(part.partType()))
                .extracting(part -> part.payload()).containsExactlyElementsOf(parts.stream().map(ChatMessagePart::payload).toList());
        assertThat(single.get(user, selectedShare.id()).snapshot().messages().getLast().parts())
                .filteredOn(part -> "THINKING".equals(part.partType()) || "TOOL".equals(part.partType()))
                .extracting(part -> part.payload()).containsExactlyElementsOf(parts.stream().map(ChatMessagePart::payload).toList());
    }

    @Test
    void noStoreDropsAllNewBusinessPartsButKeepsThemLive() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("not stored")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);
        List<ChatEvent> events = normalizer.normalize("r", "s", PROCESS);
        assertThat(events).hasSize(4).allSatisfy(event -> {
            assertThat(processEvent(event)).isTrue();
            assembly.observe(event);
            assertThat(new AgentDataPersistenceEventPolicy().retention(event, state))
                    .isEqualTo(AgentDataPersistenceEventPolicy.EventRetention.LIVE_ONLY);
        });
        assertThat(assembly.parts()).isEmpty();
        assertThat(assembly.finalContent()).isEqualTo("not stored");
    }

    @Test
    void snapshotAndRefusalKeepTheirExistingPrecedence() {
        AssistantAssembly assembly = new AssistantAssembly();
        normalizer.normalize("r", "s", "data: {\"content\":\"old\"}\n\n" + PROCESS).forEach(assembly::observe);
        assembly.observe(MessageSnapshotEvent.of("r", "s", "snapshot"));
        assertThat(assembly.finalContent()).isEqualTo("snapshot");
        assembly.observe(RuntimeEvent.metadata("r", "s", Map.of("source", "domain-agent",
                "sourceType", "agent.refusal", "metadataType", "domain_agent_control", "supervisorAction", "REROUTE")));
        normalizer.normalize("r", "s", "{\"content\":\"new\"}").forEach(assembly::observe);
        assertThat(assembly.finalContent()).isEqualTo("new");
    }

    private static boolean processEvent(ChatEvent event) {
        return "runtime.thinking".equals(event.type()) || "runtime.tool".equals(event.type());
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
