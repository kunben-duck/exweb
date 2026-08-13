package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.huawei.it.ex.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.facade.ChatSessionFacade;
import com.huawei.it.ex.one.application.facade.ChatSessionFirstAssistantSummary;
import com.huawei.it.ex.one.application.facade.FinanceChatFacade;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.integration.conversation.SessionListFilter;
import com.huawei.it.ex.one.application.integration.trace.TraceContextProvider;
import com.huawei.it.ex.one.application.service.chat.ChatFeedbackApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatRunApplicationService;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.ChatSessionNumberPage;
import com.huawei.it.ex.one.domain.chat.ChatSessionPage;
import com.huawei.it.ex.one.domain.chat.ChatStreamTopics;
import com.huawei.it.ex.one.domain.runtime.RelayOutputModeMetadata;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatAgentModeDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatAgentModeSelectionDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatAttachmentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessageDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatSelectedIntentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatSessionDto;
import com.huawei.it.ex.one.interfaces.chat.dto.CreateChatRunRequest;
import com.huawei.it.ex.one.interfaces.chat.dto.MessageFeedbackDto;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
class ChatProtocolConvergenceTest {

    @Test
    void translatorKeepsSessionTitleLanguageOutsideMetadata() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", "session1", null, "分析资金情况", "NEXT", null, null, null,
                null, null, null, null, null, List.of(), null, null, null,
                Map.of("scene", "fund"), null, null, null, null, " en_US ", " mobile ");

        ChatCommand command = translator.toCommand(request);

        assertThat(command.language()).isEqualTo("en_US");
        assertThat(command.channel()).isEqualTo("mobile");
        assertThat(command.metadata()).containsExactlyEntriesOf(Map.of("scene", "fund"));
        assertThat(command.metadata()).doesNotContainKey("language");
    }

    @Test
    void runChannelIsLimitedToSixtyFourCharacters() {
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", null, null, "移动端问题", "NEXT", null, null, null,
                null, null, null, null, null, List.of(), null, null, null,
                Map.of(), null, null, null, null, null, "m".repeat(65));

        var violations = jakarta.validation.Validation.buildDefaultValidatorFactory()
                .getValidator()
                .validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("channel");
    }

    @Test
    void translatorCarriesMultiDimensionalAgentModeOutsideMetadata() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", "session1", null, "分析资金情况", "NEXT", null, null, null,
                null, null, null, null, null, List.of(), null, null, null,
                Map.of("scene", "fund"), null, null,
                new ChatAgentModeDto(List.of(
                        new ChatAgentModeSelectionDto(" thinking ", " deep ", " 深度思考 "),
                        new ChatAgentModeSelectionDto("execution", "long_task", "长任务执行"))));

        ChatCommand command = translator.toCommand(request);

        assertThat(command.agentMode().selections())
                .extracting("scheme", "code", "displayName")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("thinking", "deep", "深度思考"),
                        org.assertj.core.groups.Tuple.tuple("execution", "long_task", "长任务执行"));
        assertThat(command.metadata()).containsExactlyEntriesOf(Map.of("scene", "fund"));
        assertThat(command.metadata()).doesNotContainKey("agentMode");
    }

    @Test
    void translatorPreservesExplicitEmptyAgentModeSnapshot() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", "session1", null, "分析资金情况", "NEXT", null, null, null,
                null, null, null, null, null, List.of(), null, null, null,
                Map.of(), null, null, new ChatAgentModeDto(List.of()));

        ChatCommand command = translator.toCommand(request);

        assertThat(command.agentMode()).isNotNull();
        assertThat(command.agentMode().emptyProfile()).isTrue();
    }

    @Test
    void translatorRejectsDuplicateAgentModeScheme() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", "session1", null, "分析资金情况", "NEXT", null, null, null,
                null, null, null, null, null, List.of(), null, null, null,
                Map.of(), null, null,
                new ChatAgentModeDto(List.of(
                        new ChatAgentModeSelectionDto("thinking", "fast", null),
                        new ChatAgentModeSelectionDto("thinking", "deep", null))));

        assertThatThrownBy(() -> translator.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("重复 scheme");
    }

    @Test
    void translatorKeepsOnlyConversationMessageAndAttachments() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1",
                "session1",
                "conversation1",
                "分析一下这个文档",
                List.of(new ChatAttachmentDto("doc1", "invoice.pdf", "application/pdf", 100L, 12L, "LOCAL_UPLOAD")),
                Map.of("clientMessageId", "front1")
        );

        var command = translator.toCommand(request);

        assertThat(command.commandId()).isEqualTo("cmd1");
        assertThat(command.channel()).isNull();
        assertThat(command.message()).isEqualTo("分析一下这个文档");
        assertThat(command.attachments()).hasSize(1);
        assertThat(command.metadata()).containsExactlyEntriesOf(Map.of("clientMessageId", "front1"));
    }

    @Test
    void translatorRemovesRelayRuntimeProfileMetadata() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1",
                "session1",
                "conversation1",
                "分析一下资产负债率",
                List.of(),
                Map.of(
                        "scene", "finance",
                        "_relayRuntimeProfile", Map.of("runtimeProfile", "DOMAIN_EXPERT"),
                        "runtimeProfile", "DOMAIN_EXPERT",
                        "relayAppMode", "domain_expert",
                        "relayRoleName", "system-awareness",
                        RelayOutputModeMetadata.RUN_METADATA_KEY, true));

        ChatCommand command = translator.toCommand(request);

        assertThat(command.metadata()).containsExactlyEntriesOf(Map.of("scene", "finance"));
    }

    @Test
    void translatorCarriesAppTagOutsideMetadata() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", null, null, "分析资金情况", "NEXT", null, null, null,
                null, null, null, null, null, List.of(), null, null, null,
                Map.of("scene", "fund"), " fund-app ", " 资金助手 ");

        ChatCommand command = translator.toCommand(request);

        assertThat(command.appId()).isEqualTo("fund-app");
        assertThat(command.appName()).isEqualTo("资金助手");
        assertThat(command.metadata()).containsExactlyEntriesOf(Map.of("scene", "fund"));
        assertThat(command.metadata()).doesNotContainKeys("appId", "appName");
    }

    @Test
    void translatorRejectsAppNameWithoutAppId() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", null, null, "分析资金情况", "NEXT", null, null, null,
                null, null, null, null, null, List.of(), null, null, null,
                Map.of(), null, "资金助手");

        assertThatThrownBy(() -> translator.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("appName 不能脱离 appId");
    }

    @Test
    void translatorMapsForceRerouteToUserCorrectionRouteTrigger() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1",
                "session1",
                "conversation1",
                "帮我重新判断应该用哪个技能",
                "NEXT",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                Map.of("routeTrigger", "first_turn")
        );

        ChatCommand command = translator.toCommand(request);

        assertThat(command.routeTrigger()).isEqualTo("user_correction");
        assertThat(command.metadata()).containsEntry("routeTrigger", "first_turn");
    }

    @Test
    void translatorRejectsForceRerouteWithExplicitTarget() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1",
                "session1",
                "conversation1",
                "帮我重新判断应该用哪个技能",
                "NEXT",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                null,
                List.of(),
                "DOMAIN_AGENT",
                "skill-a",
                null,
                Map.of()
        );

        assertThatThrownBy(() -> translator.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forceReroute=true");
    }

    @Test
    void translatorRejectsForceRerouteForInteractionContinuation() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1",
                "session1",
                "conversation1",
                null,
                "CONTINUE_INTERACTION",
                null,
                null,
                null,
                true,
                "interaction1",
                null,
                null,
                Map.of("问题", "答案"),
                List.of(),
                null,
                null,
                null,
                Map.of()
        );

        assertThatThrownBy(() -> translator.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CONTINUE_INTERACTION")
                .hasMessageContaining("forceReroute");
    }

    @Test
    void translatorCarriesSelectedIntentOnlyInInternalCommandContext() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", "session1", "conversation1", "查询资金情况", "NEXT",
                null, null, null, null, null, null, null, null, List.of(),
                "DOMAIN_AGENT", "fund-agent",
                new ChatSelectedIntentDto(" fund_management ", " 资金管理 "),
                Map.of("scene", "fund"));

        ChatCommand command = translator.toCommand(request);

        assertThat(SelectedIntentContext.intentId(command.metadata())).isEqualTo("fund_management");
        assertThat(SelectedIntentContext.intentName(command.metadata())).isEqualTo("资金管理");
        assertThat(SelectedIntentContext.removeReserved(command.metadata()))
                .containsExactlyEntriesOf(Map.of("scene", "fund"));
    }

    @Test
    void translatorRejectsSelectedIntentWithoutExplicitDomainAgent() {
        ChatRequestTranslator translator = new ChatRequestTranslator();
        CreateChatRunRequest request = new CreateChatRunRequest(
                "cmd1", "session1", "conversation1", "查询资金情况", "NEXT",
                null, null, null, null, null, null, null, null, List.of(),
                null, null, new ChatSelectedIntentDto(null, "资金管理"), Map.of());

        assertThatThrownBy(() -> translator.toCommand(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetType=DOMAIN_AGENT");
    }

    @Test
    void runsEndpointReturnsRunIdentifiersWithoutProtocolUrls() {
        AtomicReference<RuntimeForwardHeaders> startHeaders = new AtomicReference<>();
        AtomicReference<RuntimeForwardHeaders> stopHeaders = new AtomicReference<>();
        AtomicReference<TraceContext> startTrace = new AtomicReference<>();
        AtomicReference<TraceContext> stopTrace = new AtomicReference<>();
        AtomicInteger traceResolutions = new AtomicInteger();
        ThreadLocal<String> entryTrace = new ThreadLocal<>();
        TraceContextProvider traceProvider = () -> {
            traceResolutions.incrementAndGet();
            return new TraceContext(entryTrace.get());
        };
        FinanceChatFacade chatFacade = new RunStartOnlyChatFacade(
                new ChatRunStartResult("run1", "session1", 10L, Instant.parse("2026-05-16T00:00:00Z"),
                        ChatStreamTopics.runTopic("run1")),
                new ChatRunStopResult("run1", "session1", ChatRunStatus.CANCELLED, 12L,
                        Instant.parse("2026-05-16T00:00:01Z")),
                startHeaders,
                stopHeaders,
                startTrace,
                stopTrace
        );
        ChatStreamApplicationService streamService = null;
        ChatController controller = new ChatController(
                chatFacade,
                streamService,
                null,
                null,
                () -> user(),
                traceProvider,
                new PermissionChecker(),
                new ChatRequestTranslator(),
                new ChatEventTranslator(),
                new ChatTurnStreamTranslator(),
                new RuntimeForwardHeaderExtractor(new AgentRuntimeForwardCookieProperties()),
                new ChatStreamProperties()
        );
        CreateChatRunRequest request = new CreateChatRunRequest("cmd1", "session1", null, "你好", List.of(), Map.of());

        entryTrace.set("run-trace-1");
        var runStart = controller.startRun(request, "finex_proxy_profile=profile1").block();

        assertThat(runStart).isNotNull();
        assertThat(runStart.runId()).isEqualTo("run1");
        assertThat(runStart.sessionId()).isEqualTo("session1");
        assertThat(runStart.firstSeq()).isEqualTo(10L);
        assertThat(runStart.streamTopicId()).isEqualTo("chat-run-run1");
        assertThat(startHeaders.get()).isNotNull();
        assertThat(startHeaders.get().cookieHeader()).isEqualTo("finex_proxy_profile=profile1");
        assertThat(startTrace.get()).isEqualTo(new TraceContext("run-trace-1"));

        entryTrace.set("stop-trace-1");
        var stopResult = controller.stopRun("run1", "finex_proxy_profile=profile1").block();

        assertThat(stopResult).isNotNull();
        assertThat(stopResult.status()).isEqualTo("CANCELLED");
        assertThat(stopResult.latestSeq()).isEqualTo(12L);
        assertThat(stopHeaders.get()).isNotNull();
        assertThat(stopHeaders.get().cookieHeader()).isEqualTo("finex_proxy_profile=profile1");
        assertThat(stopTrace.get()).isEqualTo(new TraceContext("stop-trace-1"));
        assertThat(traceResolutions).hasValue(2);
        entryTrace.remove();
    }

    @Test
    void traceProviderFailureFallsBackToEmptyContextAtEntry() {
        AtomicReference<TraceContext> startTrace = new AtomicReference<>();
        FinanceChatFacade chatFacade = new RunStartOnlyChatFacade(
                new ChatRunStartResult("run1", "session1", 10L, Instant.parse("2026-05-16T00:00:00Z"),
                        ChatStreamTopics.runTopic("run1")),
                new ChatRunStopResult("run1", "session1", ChatRunStatus.CANCELLED, 12L,
                        Instant.parse("2026-05-16T00:00:01Z")),
                new AtomicReference<>(), new AtomicReference<>(), startTrace, new AtomicReference<>()
        );
        ChatController controller = new ChatController(
                chatFacade,
                null,
                null,
                null,
                () -> user(),
                () -> {
                    throw new IllegalStateException("jalor context unavailable");
                },
                new PermissionChecker(),
                new ChatRequestTranslator(),
                new ChatEventTranslator(),
                new ChatTurnStreamTranslator(),
                new RuntimeForwardHeaderExtractor(new AgentRuntimeForwardCookieProperties()),
                new ChatStreamProperties()
        );

        var result = controller.startRun(
                new CreateChatRunRequest("cmd1", "session1", null, "你好", List.of(), Map.of()), null).block();

        assertThat(result).isNotNull();
        assertThat(startTrace.get()).isEqualTo(TraceContext.empty());
    }

    @Test
    void eventResumeEndpointsUseBusinessNamesInsteadOfTransportNames() {
        List<String> getMappings = Arrays.stream(ChatController.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(GetMapping.class))
                .filter(mapping -> mapping != null)
                .flatMap(mapping -> Arrays.stream(mapping.value()))
                .toList();

        assertThat(getMappings)
                .contains("/sessions/{sessionId}/events/resume", "/runs/{runId}/events/resume");
        assertThat(getMappings)
                .noneMatch(path -> path.contains("/events/" + "sse"));
    }

    @Test
    void historyMessageDtoExposesAssistantSourceAfterRunId() {
        List<String> components = Arrays.stream(ChatMessageDto.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(components).containsSubsequence("runId", "assistantSource", "originType");
        assertThat(components).containsSubsequence(
                "regeneratedFromMessageId", "metadataJson", "parts");
    }

    @Test
    void sessionDtoPlacesFirstAssistantMetadataAfterItsAnswer() {
        List<String> components = Arrays.stream(ChatSessionDto.class.getRecordComponents())
                .map(component -> component.getName())
                .toList();

        assertThat(components).containsSubsequence(
                "firstAssistantAnswer", "firstAssistantMetadataJson", "createdAt");
    }

    @Test
    void sessionPaginationMapsFirstAssistantContentAndRawMetadataTogether() {
        ChatSessionFacade facade = mock(ChatSessionFacade.class);
        UserContext user = user();
        Instant now = Instant.parse("2026-08-13T00:00:00Z");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "title", "ACTIVE", "web", now, now);
        SessionListFilter filter = SessionListFilter.empty();
        ChatSessionFirstAssistantSummary summary = new ChatSessionFirstAssistantSummary(
                "第一条回答", "not-json");
        when(facade.listSessions(user, filter, null, 20))
                .thenReturn(new ChatSessionPage(List.of(session), null));
        when(facade.listSessionsByPage(user, filter, 1, 20))
                .thenReturn(new ChatSessionNumberPage(List.of(session), 1, 20, 1, 1));
        when(facade.findFirstAssistantSummaries(user, List.of(session)))
                .thenReturn(Map.of(session.id(), summary));
        when(facade.getSession(user, session.id())).thenReturn(session);
        ChatSessionController controller = new ChatSessionController(
                facade,
                mock(ChatFeedbackApplicationService.class),
                mock(ChatRunApplicationService.class),
                () -> user,
                new PermissionChecker(),
                new ChatMessageVersionViewAssembler());

        var cursorPage = controller.list(null, null, null, null, null, 20).block();
        var numberPage = controller.listByPage(null, null, null, null, 1, 20).block();
        ChatSessionDto detail = controller.get(session.id()).block();

        assertThat(cursorPage).isNotNull();
        assertThat(cursorPage.items().getFirst().firstAssistantAnswer()).isEqualTo("第一条回答");
        assertThat(cursorPage.items().getFirst().firstAssistantMetadataJson()).isEqualTo("not-json");
        assertThat(numberPage).isNotNull();
        assertThat(numberPage.items().getFirst().firstAssistantAnswer()).isEqualTo("第一条回答");
        assertThat(numberPage.items().getFirst().firstAssistantMetadataJson()).isEqualTo("not-json");
        assertThat(detail).isNotNull();
        assertThat(detail.firstAssistantAnswer()).isNull();
        assertThat(detail.firstAssistantMetadataJson()).isNull();
        verify(facade, times(2)).findFirstAssistantSummaries(user, List.of(session));
    }

    @Test
    void historyMessageDtoKeepsRawMetadataJsonUnchanged() {
        for (String metadataJson : Arrays.asList(null, "", "not-json", "{\"partial\":true}")) {
            ChatMessageDto dto = messageDto(metadataJson);

            assertThat(dto.metadataJson()).isSameAs(metadataJson);
        }

        var json = new ObjectMapper().findAndRegisterModules()
                .valueToTree(messageDto("{\"partial\":true}"));
        assertThat(json.path("metadataJson").asText()).isEqualTo("{\"partial\":true}");
    }

    @Test
    void feedbackDtoExposesStructuredMetadataObject() {
        Instant now = Instant.parse("2026-08-10T00:00:00Z");
        MessageFeedbackDto populated = new MessageFeedbackDto(
                "feedback1", "message1", "run1", "LIKE", "ACTIVE", null, null,
                Map.of("clientTraceId", "trace1"), now, now);
        MessageFeedbackDto empty = new MessageFeedbackDto(
                "feedback2", "message2", null, null, "CANCELLED", null, null,
                Map.of(), now, now);

        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        assertThat(objectMapper.valueToTree(populated).path("metadata").path("clientTraceId").asText())
                .isEqualTo("trace1");
        assertThat(objectMapper.valueToTree(empty).path("metadata").isObject()).isTrue();
        assertThat(objectMapper.valueToTree(empty).path("metadata").isEmpty()).isTrue();
    }

    @Test
    void turnStreamWrapsChatEventWithoutChangingEventContract() {
        ChatTurnStreamTranslator translator = new ChatTurnStreamTranslator();
        ChatEventDto event = new ChatEventDto("run1", "session1", 12L, "message.delta",
                Map.of("delta", "hi"));

        var streamItem = translator.streamItem(event);
        var heartbeat = translator.heartbeat("session1", "run1", 12L);
        var done = translator.done("session1", "run1", 13L, "run.completed");

        assertThat(streamItem.type()).isEqualTo("conversation-turn-stream");
        assertThat(streamItem.payload().type()).isEqualTo("stream-item");
        assertThat(streamItem.payload().encodedItem().data()).isEqualTo(event);
        assertThat(heartbeat.payload().type()).isEqualTo("heartbeat");
        assertThat(heartbeat.payload().encodedItem()).isNull();
        assertThat(done.payload().type()).isEqualTo("done");
        assertThat(done.payload().terminalEventType()).isEqualTo("run.completed");
    }

    private ChatMessageDto messageDto(String metadataJson) {
        return new ChatMessageDto(
                "message1", "session1", null, 1L, 0, 1,
                "assistant", "answer", null, "run1", "relay", "NORMAL", false,
                null, null, null, null, metadataJson,
                List.of(), List.of(), null, null, Instant.parse("2026-08-10T00:00:00Z"));
    }

    @Test
    void rejectsCookieHeaderAboveConfiguredRuntimeForwardLimit() {
        AgentRuntimeForwardCookieProperties properties = new AgentRuntimeForwardCookieProperties();
        properties.setMaxLength(4);
        RuntimeForwardHeaderExtractor extractor = new RuntimeForwardHeaderExtractor(properties);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> extractor.fromCookieHeader("abcdef"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cookie 请求头超过最大允许长度");
    }

    private record RunStartOnlyChatFacade(ChatRunStartResult runStart,
                                          ChatRunStopResult stopResult,
                                          AtomicReference<RuntimeForwardHeaders> startHeaders,
                                          AtomicReference<RuntimeForwardHeaders> stopHeaders,
                                          AtomicReference<TraceContext> startTrace,
                                          AtomicReference<TraceContext> stopTrace) implements FinanceChatFacade {
        @Override
        public Flux<ChatEvent> executeRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders) {
            return Flux.error(new UnsupportedOperationException("executeRun is not used by this test"));
        }

        @Override
        public Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders) {
            startHeaders.set(forwardHeaders);
            return Mono.just(runStart);
        }

        @Override
        public Mono<ChatRunStartResult> startRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                                 RuntimeForwardHeaders forwardHeaders) {
            startTrace.set(traceContext);
            return startRun(user, command, forwardHeaders);
        }

        @Override
        public Mono<ChatRunStopResult> stopRun(UserContext user, String runId, RuntimeForwardHeaders forwardHeaders) {
            stopHeaders.set(forwardHeaders);
            return Mono.just(stopResult);
        }

        @Override
        public Mono<ChatRunStopResult> stopRun(UserContext user, TraceContext traceContext, String runId,
                                               RuntimeForwardHeaders forwardHeaders) {
            stopTrace.set(traceContext);
            return stopRun(user, runId, forwardHeaders);
        }
    }

    private static UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }
}
