package com.huawei.finance.front.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.config.ChatStreamProperties;
import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRunStartResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatAttachmentDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatMessageDto;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatRunRequest;
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
