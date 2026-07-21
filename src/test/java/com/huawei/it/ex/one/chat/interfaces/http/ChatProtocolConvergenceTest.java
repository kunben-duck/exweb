package com.huawei.it.ex.one.chat.interfaces.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.huawei.it.ex.one.common.http.AgentRuntimeForwardCookieProperties;
import com.huawei.it.ex.one.chat.application.service.ChatApplicationService;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.metadata.SelectedIntentContext;
import com.huawei.it.ex.one.common.trace.TraceContextProvider;
import com.huawei.it.ex.one.security.application.service.PermissionChecker;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatRunStartResult;
import com.huawei.it.ex.one.chat.domain.ChatRunStatus;
import com.huawei.it.ex.one.chat.domain.ChatRunStopResult;
import com.huawei.it.ex.one.chat.domain.ChatStreamTopics;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatAttachmentDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatEventDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatMessageDto;
import com.huawei.it.ex.one.chat.interfaces.dto.ChatSelectedIntentDto;
import com.huawei.it.ex.one.chat.interfaces.dto.CreateChatRunRequest;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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
        AtomicReference<TraceContext> startTrace = new AtomicReference<>();
        AtomicReference<TraceContext> stopTrace = new AtomicReference<>();
        AtomicInteger traceResolutions = new AtomicInteger();
        ThreadLocal<String> entryTrace = new ThreadLocal<>();
        TraceContextProvider traceProvider = () -> {
            traceResolutions.incrementAndGet();
            return new TraceContext(entryTrace.get());
        };
        ChatApplicationService chatFacade = new RunStartOnlyChatFacade(
                new ChatRunStartResult("run1", "session1", 10L, Instant.parse("2026-05-16T00:00:00Z"),
                        ChatStreamTopics.runTopic("run1")),
                new ChatRunStopResult("run1", "session1", ChatRunStatus.CANCELLED, 12L,
                        Instant.parse("2026-05-16T00:00:01Z")),
                startHeaders,
                stopHeaders,
                startTrace,
                stopTrace
        );
        ChatController controller = new ChatController(
                chatFacade,
                new ChatRequestTranslator(),
                new ChatRequestContextResolver(
                        () -> user(), traceProvider, new PermissionChecker(),
                        new RuntimeForwardHeaderExtractor(new AgentRuntimeForwardCookieProperties()))
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
        ChatApplicationService chatFacade = new RunStartOnlyChatFacade(
                new ChatRunStartResult("run1", "session1", 10L, Instant.parse("2026-05-16T00:00:00Z"),
                        ChatStreamTopics.runTopic("run1")),
                new ChatRunStopResult("run1", "session1", ChatRunStatus.CANCELLED, 12L,
                        Instant.parse("2026-05-16T00:00:01Z")),
                new AtomicReference<>(), new AtomicReference<>(), startTrace, new AtomicReference<>()
        );
        ChatController controller = new ChatController(
                chatFacade,
                new ChatRequestTranslator(),
                new ChatRequestContextResolver(
                        () -> user(),
                        () -> {
                            throw new IllegalStateException("jalor context unavailable");
                        },
                        new PermissionChecker(),
                        new RuntimeForwardHeaderExtractor(new AgentRuntimeForwardCookieProperties()))
        );

        var result = controller.startRun(
                new CreateChatRunRequest("cmd1", "session1", null, "你好", List.of(), Map.of()), null).block();

        assertThat(result).isNotNull();
        assertThat(startTrace.get()).isEqualTo(TraceContext.empty());
    }

    @Test
    void eventResumeEndpointsUseBusinessNamesInsteadOfTransportNames() {
        List<String> getMappings = Arrays.stream(ChatEventStreamController.class.getDeclaredMethods())
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
                                          AtomicReference<RuntimeForwardHeaders> stopHeaders,
                                          AtomicReference<TraceContext> startTrace,
                                          AtomicReference<TraceContext> stopTrace) implements ChatApplicationService {
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
