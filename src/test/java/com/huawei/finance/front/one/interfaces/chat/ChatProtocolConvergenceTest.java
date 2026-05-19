package com.huawei.finance.front.one.interfaces.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.application.service.ChatStreamApplicationService;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRunStartResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStatus;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import com.huawei.finance.front.one.domain.chat.ChatStreamTopics;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatAttachmentDto;
import com.huawei.finance.front.one.interfaces.chat.dto.CreateChatRunRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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
        FinanceChatFacade chatFacade = new RunStartOnlyChatFacade(
                new ChatRunStartResult("run1", "session1", 10L, Instant.parse("2026-05-16T00:00:00Z"),
                        ChatStreamTopics.runTopic("run1")),
                new ChatRunStopResult("run1", "session1", ChatRunStatus.CANCELLED, 12L,
                        Instant.parse("2026-05-16T00:00:01Z"))
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
                new ChatEventTranslator()
        );
        CreateChatRunRequest request = new CreateChatRunRequest("cmd1", "session1", null, "你好", List.of(), Map.of());

        var runStart = controller.startRun(request).block();

        assertThat(runStart).isNotNull();
        assertThat(runStart.runId()).isEqualTo("run1");
        assertThat(runStart.sessionId()).isEqualTo("session1");
        assertThat(runStart.firstSeq()).isEqualTo(10L);
        assertThat(runStart.streamTopicId()).isEqualTo("chat-run-run1");

        var stopResult = controller.stopRun("run1").block();

        assertThat(stopResult).isNotNull();
        assertThat(stopResult.status()).isEqualTo("CANCELLED");
        assertThat(stopResult.latestSeq()).isEqualTo(12L);
    }

    private record RunStartOnlyChatFacade(ChatRunStartResult runStart, ChatRunStopResult stopResult) implements FinanceChatFacade {
        @Override
        public Flux<ChatEvent> executeRun(UserContext user, ChatCommand command) {
            return Flux.error(new UnsupportedOperationException("executeRun is not used by this test"));
        }

        @Override
        public Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command) {
            return Mono.just(runStart);
        }

        @Override
        public Mono<ChatRunStopResult> stopRun(UserContext user, String runId) {
            return Mono.just(stopResult);
        }
    }

    private static UserContext user() {
        return new UserContext("tenant1", "user1", "User One");
    }
}
