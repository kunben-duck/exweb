package com.huawei.it.ex.one.infrastructure.runtime.intentagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteFrame;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamClient;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamFrame;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

class StreamingIntentAgentRuntimeTest {
    @Test
    void mapsStreamingFramesToExistingIntentAgentContract() {
        IntentDecision decision = new IntentDecision(
                "intent-1", "知识问答", TaskComplexity.SIMPLE, 0.9, true,
                "skill-1", Map.of(), List.of(), Map.of());
        IntentDecisionStreamClient client = (command, memory, user) -> Flux.just(
                IntentDecisionStreamFrame.progress(
                        Map.of("stage", "ES_SEARCHING", "stageMessage", "ES检索中"), 1, 4),
                IntentDecisionStreamFrame.delta(Map.of("index", 1L, "content", "partial"), 1, 4),
                IntentDecisionStreamFrame.result(IntentRecognitionResult.finalDecision(decision), 1, 4)
        );
        StreamingIntentAgentRuntime runtime = new StreamingIntentAgentRuntime(client);

        List<IntentAgentRouteFrame> frames = runtime.route(request()).collectList().block();

        assertThat(frames).hasSize(4);
        assertThat(frames.get(0).event().type()).isEqualTo("runtime.progress");
        assertThat(frames.get(0).event().payload())
                .containsEntry("sourceType", "intent-start")
                .containsEntry("stage", "intent_calling");
        assertThat(frames.get(1).event().type()).isEqualTo("runtime.progress");
        assertThat(frames.get(1).event().payload())
                .containsEntry("sourceType", "intent-progress")
                .containsEntry("stage", "ES_SEARCHING")
                .containsEntry("message", "ES检索中")
                .containsEntry("attempt", 1)
                .containsEntry("maxAttempts", 4);
        assertThat(frames.get(2).event().type()).isEqualTo("runtime.thinking");
        assertThat(frames.get(2).event().payload())
                .containsEntry("sourceType", "intent-delta")
                .containsEntry("stage", "LLM_PROCESSING")
                .containsEntry("index", 1L)
                .containsEntry("text", "partial");
        assertThat(frames.get(3).result().recognitionResult().decision()).isEqualTo(decision);
        assertThat(frames.get(3).result().latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void propagatesCancellationToTheStreamingClient() {
        AtomicBoolean cancelled = new AtomicBoolean();
        IntentDecisionStreamClient client = (command, memory, user) -> Flux
                .<IntentDecisionStreamFrame>never()
                .doOnCancel(() -> cancelled.set(true));
        StreamingIntentAgentRuntime runtime = new StreamingIntentAgentRuntime(client);

        Disposable subscription = runtime.route(request()).subscribe();
        subscription.dispose();

        assertThat(cancelled).isTrue();
    }

    @Test
    void forwardsTrustedUserMessageIdToStreamingClient() {
        AtomicReference<String> captured = new AtomicReference<>();
        IntentDecision decision = new IntentDecision(
                "intent-1", "知识问答", TaskComplexity.SIMPLE, 0.9, true,
                "skill-1", Map.of(), List.of(), Map.of());
        IntentDecisionStreamClient client = new IntentDecisionStreamClient() {
            @Override
            public Flux<IntentDecisionStreamFrame> recognize(
                    ChatCommand command, MemoryContext memory, UserContext user) {
                throw new AssertionError("legacy overload should not be used");
            }

            @Override
            public Flux<IntentDecisionStreamFrame> recognize(
                    ChatCommand command, MemoryContext memory, UserContext user, String userMessageId) {
                captured.set(userMessageId);
                return Flux.just(IntentDecisionStreamFrame.result(
                        IntentRecognitionResult.finalDecision(decision), 1, 1));
            }
        };

        new StreamingIntentAgentRuntime(client).route(request("msg-user")).collectList().block();

        assertThat(captured.get()).isEqualTo("msg-user");
    }

    private IntentAgentRouteRequest request() {
        return request(null);
    }

    private IntentAgentRouteRequest request(String userMessageId) {
        UserContext user = new UserContext("tenant1", "user1", "Alice");
        ChatSession session = new ChatSession(
                "session1", "tenant1", "user1", "test", "ACTIVE", "web", Instant.now(), Instant.now());
        ChatCommand command = new ChatCommand(
                "command1", "tenant1", "user1", "session1", null, "web", "问题", List.of(), Map.of());
        return new IntentAgentRouteRequest(
                user, session, command, MemoryContext.empty(), "run1", "first_turn", userMessageId);
    }
}
