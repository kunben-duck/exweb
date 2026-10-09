/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.application.config.ChatWebSocketProperties;
import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatMessage;
import com.huawei.it.ex.one.domain.chat.ChatMessagePart;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentRuntime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

class DomainAgentThinkTitlePersistenceTest extends ChatFlowTestSupport {
    private static final String LONG_TITLE = "中😀A".repeat(85) + "😀Z";
    private final UserContext user = new UserContext("tenant1", "user1", "User One");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(mapper);

    @ParameterizedTest
    @MethodSource("boundaryTitles")
    void onlyDisplayTitleIsLimitedByUnicodeCodePoints(String title) throws Exception {
        ChatEvent event = normalizer.normalize("run1", "session1", thinkFrame(title)).getFirst();
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(event);

        var part = assembly.parts().getFirst();
        int length = Math.min(256, title.codePointCount(0, title.length()));
        assertThat(part.title()).isEqualTo(title.substring(0, title.offsetByCodePoints(0, length)));
        assertThat(part.title().codePointCount(0, part.title().length())).isEqualTo(length);
        assertThat(Character.isHighSurrogate(part.title().charAt(part.title().length() - 1))).isFalse();
        assertThat(part.contentText()).isEqualTo(title);
        assertThat(part.payload()).containsAllEntriesOf(event.payload()).containsEntry("thinkTitle", title);
        assertThat(event.payload()).containsEntry("thinkTitle", title).doesNotContainKeys("title", "text", "status");
        assertThat(assembly.finalContent()).isEmpty();
    }

    private static Stream<String> boundaryTitles() {
        return Stream.of("a", "中", "😀", "A中😀").flatMap(alphabet -> Stream.of(255, 256, 257).map(count -> {
            String repeated = alphabet.repeat(count);
            return repeated.substring(0, repeated.offsetByCodePoints(0, count));
        }));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void absentOrBlankTitleKeepsDefaultAndFullContent(String title) throws Exception {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("type", "think");
        payload.put("thinkState", "start");
        payload.put("thinkContent", LONG_TITLE);
        payload.put("thinkTitle", title);
        AssistantAssembly assembly = new AssistantAssembly();
        normalizer.normalize("run1", "session1", mapper.writeValueAsString(payload)).forEach(assembly::observe);
        assertThat(assembly.parts().getFirst().title()).isEqualTo("思考过程");
        assertThat(assembly.parts().getFirst().contentText()).isEqualTo(LONG_TITLE);
    }

    @Test
    void shortTitleIsNotTrimmedAndContentTakesPrecedence() {
        AssistantAssembly assembly = new AssistantAssembly();
        normalizer.normalize("run1", "session1",
                "{\"type\":\"think\",\"thinkTitle\":\" 分析 😀 \",\"thinkContent\":\"完整思考内容\"}")
                .forEach(assembly::observe);
        assertThat(assembly.parts().getFirst().title()).isEqualTo(" 分析 😀 ");
        assertThat(assembly.parts().getFirst().contentText()).isEqualTo("完整思考内容");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionAndWaitingPersistWithTitleColumnConstraint(boolean waiting) throws Exception {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        TitleConstrainedMessageRepository messages = new TitleConstrainedMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        String frames = thinkFrame(LONG_TITLE) + "data: {\"content\":\"answer\"}\n\n" + (waiting
                ? "data: {\"type\":\"approval-request\",\"approval_id\":\"q1\","
                        + "\"operation_type\":\"questionnaire\",\"questions\":[{\"question\":\"期间\"}]}\n\n"
                : "data: {\"endFlag\":true}\n\n");
        DomainAgentClient client = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                return Flux.fromIterable(normalizer.normalize(request.runId(), request.sessionId(), frames));
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        FinanceEXChatService service = financeServiceWithDomainClientAndBindings(
                sessions, messages, runs, events, repeatedDomainAgentRouteService(new AtomicInteger(), "skill1"),
                client, new DomainAgentRuntime(client), runtimeBindingRepository(), new DomainAgentProperties());
        List<ChatEvent> output = service.executeRun(user, new ChatCommand(
                        "cmd1", null, null, null, null, "web", "Analyze", List.of(), Map.of()))
                .collectList().block(Duration.ofSeconds(10));

        assertThat(output).isNotNull();
        assertThat(output).extracting(ChatEvent::type)
                .contains(waiting ? "run.waiting_user" : "run.completed").doesNotContain("run.failed");
        ChatMessage assistant = assistant(messages);
        assertThat(assistant.content()).isEqualTo("answer");
        assertPersistedTitle(assistant);
        assertThat(runs.findById(assistant.runId()).orElseThrow().status())
                .isEqualTo(waiting ? ChatRunStatus.WAITING_USER : ChatRunStatus.COMPLETED);
        assertThat(output).filteredOn(event -> "runtime.thinking".equals(event.type()))
                .singleElement().satisfies(event -> assertThat(event.payload()).containsEntry("thinkTitle", LONG_TITLE));
    }

    @Test
    void stopReplaysFullEventIntoBoundedDisplayTitle() throws Exception {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        TitleConstrainedMessageRepository messages = new TitleConstrainedMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        normalizer.normalize("run1", "session1", thinkFrame(LONG_TITLE)
                + "data: {\"content\":\"partial\"}\n\n").forEach(events::append);

        stopService(sessions, messages, runs, events)
                .stopRun(user, "run1", RuntimeForwardHeaders.empty()).block(Duration.ofSeconds(10));

        assertThat(runs.findById("run1").orElseThrow().status()).isEqualTo(ChatRunStatus.CANCELLED);
        assertThat(assistant(messages).content()).isEqualTo("partial");
        assertPersistedTitle(assistant(messages));
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPEND", "REPLACE"})
    void asyncResultsPersistWithTitleColumnConstraint(String mode) throws Exception {
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        TitleConstrainedMessageRepository messages = new TitleConstrainedMessageRepository();
        InMemoryRunRepository runs = new InMemoryRunRepository();
        InMemoryEventStore events = new InMemoryEventStore();
        seedRunningRun(sessions, messages, runs, user, "run1", "session1", "msg-user");
        Instant now = Instant.now();
        messages.save(new ChatMessage("msg-assistant", "tenant1", "user1", "session1", "msg-user",
                2L, 1, 0, "assistant", "existing", null, "run1", "NORMAL", false,
                null, null, null, null, null, List.of(), now));
        runs.save(new ChatRun("run1", "tenant1", "user1", "session1", ChatRunStatus.RUNNING,
                "DOMAIN_AGENT", "skill1", "domain-agent", null, ChatRunMode.NEXT,
                null, "msg-user", "msg-assistant", 1L, 4L, null, now, null,
                DomainAgentAsyncTaskMetadata.runningOverlay("msg-assistant", now.plusSeconds(3600)), now, now));
        PermissionChecker permissions = new PermissionChecker();
        SessionApplicationService sessionService = new SessionApplicationService(
                sessions, messages, new SequentialIdGenerator(), permissions);
        ChatStreamApplicationService streams = new ChatStreamApplicationService(
                events, new LocalChatEventStreamRegistry(), liveEventBus(), runs,
                permissions, sessions, new ChatWebSocketProperties());
        DomainAgentAsyncTaskCallbackCommitService service = new DomainAgentAsyncTaskCallbackCommitService(
                runs, new InMemoryExecutionRepository(), sessions, sessionService, streams,
                new ChatEventBatcher(new ChatStreamProperties(), mapper), mapper);
        List<ChatEvent> business = normalizer.normalize("run1", "session1",
                thinkFrame(LONG_TITLE) + "data: {\"content\":\"result\"}\n\n");

        var result = service.commit(new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                "run1", true, mode, true, business, null));

        assertThat(result.accepted()).isTrue();
        assertThat(result.run().status()).isEqualTo(ChatRunStatus.COMPLETED);
        assertPersistedTitle(assistant(messages));
        assertThat(assistant(messages).content()).isEqualTo("APPEND".equals(mode)
                ? "existing" + AssistantAssembly.DOMAIN_AGENT_CONTENT_SEGMENT_MARKER + "result" : "result");
    }

    private String thinkFrame(String title) throws JsonProcessingException {
        return "data: " + mapper.writeValueAsString(Map.of(
                "type", "think", "thinkState", "start", "thinkTitle", title)) + "\n\n";
    }

    private static ChatMessage assistant(TitleConstrainedMessageRepository messages) {
        return messages.messages.stream().filter(message -> "assistant".equals(message.role())).findFirst().orElseThrow();
    }

    private static void assertPersistedTitle(ChatMessage assistant) {
        assertThat(assistant.parts()).filteredOn(part -> "THINKING".equals(part.partType()))
                .singleElement().satisfies(part -> {
                    assertThat(part.title()).isEqualTo(LONG_TITLE.substring(0, LONG_TITLE.offsetByCodePoints(0, 256)));
                    assertThat(part.contentText()).isEqualTo(LONG_TITLE);
                    assertThat(part.payload()).containsEntry("thinkTitle", LONG_TITLE);
                });
    }

    // 模拟 title VARCHAR(256) 的边界；不替代真实数据库事务及列约束验证。
    private static final class TitleConstrainedMessageRepository extends InMemoryMessageRepository {
        @Override
        public ChatMessage save(ChatMessage message) {
            validateTitles(message);
            return super.save(message);
        }

        @Override
        public ChatMessage updateAssistantMessage(ChatMessage message) {
            validateTitles(message);
            return super.updateAssistantMessage(message);
        }

        @Override
        public ChatMessage updateAssistantAsyncResult(ChatMessage existing, ChatMessage update, boolean replace) {
            validateTitles(update);
            ChatMessage merged = super.updateAssistantAsyncResult(existing, update, replace);
            messages.set(messages.indexOf(existing), merged);
            return merged;
        }

        private static void validateTitles(ChatMessage message) {
            for (ChatMessagePart part : message.parts()) {
                if (part.title().codePointCount(0, part.title().length()) > 256) {
                    throw new DataIntegrityViolationException("title exceeds VARCHAR(256)");
                }
            }
        }
    }
}
