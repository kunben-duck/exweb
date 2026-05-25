package com.huawei.finance.front.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.chat.AttachmentRef;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class OpenAiChatCompletionRelayCodecTest {
    private final OpenAiChatCompletionRelayCodec codec = new OpenAiChatCompletionRelayCodec(new ObjectMapper());

    @Test
    void buildRequestBodyUsesChatCompletionsShape() {
        RelayAgentProperties properties = new RelayAgentProperties();
        properties.setApiAdapter("deepseek-chat-completions");
        properties.setModel("deepseek-v4-pro");
        properties.setStream(false);
        properties.setThinkingEnabled(true);
        properties.setReasoningEffort("high");

        Map<String, Object> body = codec.buildRequestBody(request("帮我分析费用"), properties);

        assertThat(body)
                .containsEntry("model", "deepseek-v4-pro")
                .containsEntry("stream", false)
                .containsEntry("thinking", Map.of("type", "enabled"))
                .containsEntry("reasoning_effort", "high");
        assertThat(body.get("messages").toString())
                .contains("You are a helpful assistant.")
                .contains("帮我分析费用")
                .contains("documentId=doc1");
    }

    @Test
    void decodeBlockingResponseExtractsAssistantMessageContent() {
        List<ChatEvent> events = codec.decodeBlockingResponse("run1", "session1",
                """
                {"choices":[{"message":{"role":"assistant","content":"你好，我可以帮你。"}}]}
                """);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("message.delta");
        assertThat(events.getFirst().payload()).containsEntry("delta", "你好，我可以帮你。");
    }

    @Test
    void decodeStreamingResponseSupportsSseDataBlocks() {
        Flux<ChatEvent> events = codec.decodeStreamingResponse("run1", "session1", Flux.just(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n",
                "data: [DONE]\n\n"
        ));

        StepVerifier.create(events)
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "你"))
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "好"))
                .verifyComplete();
    }

    @Test
    void decodeStreamingResponseSupportsDecodedJsonChunks() {
        Flux<ChatEvent> events = codec.decodeStreamingResponse("run1", "session1", Flux.just(
                "{\"choices\":[{\"delta\":{\"content\":\"hello\"}}]}",
                "[DONE]"
        ));

        StepVerifier.create(events)
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "hello"))
                .verifyComplete();
    }

    @Test
    void decodeStreamingResponseConsumesReasoningOnlyChunksBeforeContent() {
        Flux<ChatEvent> events = codec.decodeStreamingResponse("run1", "session1", Flux.just(
                "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"reasoning_content\":\"\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"\",\"reasoning_content\":\"分析\"}}]}",
                "{\"choices\":[{\"delta\":{\"content\":\"结果\"}}]}",
                "[DONE]"
        ));

        StepVerifier.create(events)
                .assertNext(event -> assertThat(event.payload()).containsEntry("delta", "结果"))
                .verifyComplete();
    }

    private AgentRuntimeRequest request(String message) {
        return new AgentRuntimeRequest(
                "tenant1",
                "user1",
                "session1",
                "run1",
                null,
                message,
                List.of(new AttachmentRef("doc1", "费用.xlsx", "application/vnd.ms-excel", 1024L)),
                MemoryContext.empty(),
                null,
                null,
                Map.of(),
                RuntimeForwardHeaders.empty()
        );
    }
}
