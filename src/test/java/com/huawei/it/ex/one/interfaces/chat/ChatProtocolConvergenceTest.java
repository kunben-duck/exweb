package com.huawei.it.ex.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.facade.FinanceChatFacade;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import com.huawei.it.ex.one.domain.chat.ChatStreamTopics;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatAttachmentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatMessageDto;
import com.huawei.it.ex.one.interfaces.chat.dto.ChatSelectedIntentDto;
import com.huawei.it.ex.one.interfaces.chat.dto.CreateChatRunRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
class ChatProtocolConvergenceTest {

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
        assertThat(command.channel()).isEqualTo("web");
        assertThat(command.message()).isEqualTo("分析一下这个文档");
        assertThat(command.attachments()).hasSize(1);
        assertThat(command.metadata()).containsExactlyEntriesOf(Map.of("clientMessageId", "front1"));
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
        FinanceChatFacade chatFacade = new RunStartOnlyChatFacade(
                new ChatRunStartResult("run1", "session1", 10L, Instant.parse("2026-05-16T00:00:00Z"),
                        ChatStreamTopics.runTopic("run1")),
                new ChatRunStopResult("run1", "session1", ChatRunStatus.CANCELLED, 12L,
                        Instant.parse("2026-05-16T00:00:01Z")),
                startHeaders,
                stopHeaders
        );
        ChatStreamApplicationService streamService = null;
        ChatController controller = new ChatController(
                chatFacade,
                streamService,
                null,
                null,
                () -> user(),
                new PermissionChecker(),
                new ChatRequestTranslator(),
                new ChatEventTranslator(),
                new ChatTurnStreamTranslator(),
                new RuntimeForwardHeaderExtractor(new AgentRuntimeForwardCookieProperties()),
                new ChatStreamProperties()
        );
        CreateChatRunRequest request = new CreateChatRunRequest("cmd1", "session1", null, "你好", List.of(), Map.of());

        var runStart = controller.startRun(request, "finex_proxy_profile=profile1").block();

        assertThat(runStart).isNotNull();
        assertThat(runStart.runId()).isEqualTo("run1");
        assertThat(runStart.sessionId()).isEqualTo("session1");
        assertThat(runStart.firstSeq()).isEqualTo(10L);
        assertThat(runStart.streamTopicId()).isEqualTo("chat-run-run1");
        assertThat(startHeaders.get()).isNotNull();
        assertThat(startHeaders.get().cookieHeader()).isEqualTo("finex_proxy_profile=profile1");

        var stopResult = controller.stopRun("run1", "finex_proxy_profile=profile1").block();

        assertThat(stopResult).isNotNull();
        assertThat(stopResult.status()).isEqualTo("CANCELLED");
        assertThat(stopResult.latestSeq()).isEqualTo(12L);
        assertThat(stopHeaders.get()).isNotNull();
        assertThat(stopHeaders.get().cookieHeader()).isEqualTo("finex_proxy_profile=profile1");
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
                                          AtomicReference<RuntimeForwardHeaders> stopHeaders) implements FinanceChatFacade {
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
        public Mono<ChatRunStopResult> stopRun(UserContext user, String runId, RuntimeForwardHeaders forwardHeaders) {
            stopHeaders.set(forwardHeaders);
            return Mono.just(stopResult);
        }
    }

    private static UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }
}
