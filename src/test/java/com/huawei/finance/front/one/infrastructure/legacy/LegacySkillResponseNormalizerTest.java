package com.huawei.finance.front.one.infrastructure.legacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacySkillResponseNormalizerTest {
    private final LegacySkillResponseNormalizer normalizer = new LegacySkillResponseNormalizer(new ObjectMapper());

    @Test
    void mapsLegacyEventStreamFramesToChatServiceEvents() {
        String chunk = """
                message: {"traceId":"trace-1"}

                message: {"sessionId":"legacy-session-1"}

                message: {"messageId":"legacy-message-1"}

                message: {"cardUrl":"https://card","intent":"tax","skillId":"skill-tax"}

                message: {"processResult":{"dynamicResponse":[{"title":"工具调用","type":"str"}]}}

                message: {"content":"你好"}

                message: {"endFlag":true}

                """;

        List<ChatEvent> events = normalizer.normalize("run1", "session1", chunk);

        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "runtime.metadata",
                "runtime.metadata",
                "runtime.metadata",
                "runtime.card",
                "runtime.thinking",
                "message.delta",
                "message.completed"
        );
        assertThat(events.get(0).payload()).containsEntry("metadataType", "trace")
                .containsEntry("traceId", "trace-1");
        assertThat(events.get(1).payload()).containsEntry("metadataType", "legacy_session")
                .containsEntry("legacySessionId", "legacy-session-1");
        assertThat(events.get(2).payload()).containsEntry("metadataType", "legacy_message")
                .containsEntry("legacyMessageId", "legacy-message-1");
        assertThat(events.get(3).payload()).containsEntry("cardUrl", "https://card")
                .containsEntry("intent", "tax")
                .containsEntry("skillId", "skill-tax");
        assertThat(events.get(5).payload()).containsEntry("delta", "你好");
        assertThat(events.get(6).payload()).containsEntry("sourceType", "legacy-agent-end");
    }

    @Test
    void mapsStandaloneIntentAndSkillToMetadata() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "message: {\"intent\":\"CreditSales\",\"skillId\":\"skill-credit\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.metadata");
        assertThat(events.getFirst().payload()).containsEntry("metadataType", "legacy_skill")
                .containsEntry("intent", "CreditSales")
                .containsEntry("skillId", "skill-credit");
    }

    @Test
    void unknownJsonFallsBackToRuntimeEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "message: {\"foo\":\"bar\"}");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("runtime.event");
        assertThat(events.get(0).payload()).containsEntry("source", "legacy-agent");
    }

    @Test
    void supportsLegacyMessagePrefixesAndRedactsSensitiveUnknownPayload() {
        String chunk = """
                message. {"content":"A"}

                message {"token":"secret","foo":"bar"}

                """;

        List<ChatEvent> events = normalizer.normalize("run1", "session1", chunk);

        assertThat(events).extracting(ChatEvent::type).containsExactly("message.delta", "runtime.event");
        assertThat(events.get(0).payload()).containsEntry("delta", "A");
        assertThat(events.get(1).payload().toString()).contains("[REDACTED]").doesNotContain("secret");
    }

    @Test
    void mapsThinkingReferencesStatesAndCards() {
        String chunk = """
                message: {"state":"THINKING","stateDesc":"思考中"}

                message: {"searchList":[{"title":"网页","url":"https://example.com"}]}

                message: {"sourcesDocuments":[{"docName":"a.pdf"}]}

                message: {"state":"GENERATE","stateDesc":"生成答案"}

                message: {"diyCardScene":{"type":"tax"},"cardList":[{"title":"卡片"}]}

                """;

        List<ChatEvent> events = normalizer.normalize("run1", "session1", chunk);

        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "runtime.thinking",
                "runtime.reference",
                "runtime.reference",
                "runtime.progress",
                "runtime.card"
        );
        assertThat(events.get(0).payload()).containsEntry("status", "STARTED")
                .containsEntry("stateDesc", "思考中");
        assertThat(events.get(1).payload()).containsEntry("referenceType", "search_list");
        assertThat(events.get(2).payload()).containsEntry("referenceType", "source_documents");
        assertThat(events.get(3).payload()).containsEntry("stage", "GENERATE");
        assertThat(events.get(4).payload()).containsEntry("cardType", "legacy-card");
    }

    @Test
    void skipsNullItemsInStructuredArrays() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"sourcesDocuments":[null,{"docName":"a.pdf"}],"cardList":[null,{"title":"卡片"}]}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.reference", "runtime.card");
        assertThat(events.get(0).payload().toString()).contains("a.pdf").doesNotContain("null");
        assertThat(events.get(1).payload().toString()).contains("卡片").doesNotContain("null");
    }

    @Test
    void splitsThinkContentFromAnswerContent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "message: {\"content\":\"<think>分析过程</think>最终答案\"}");

        assertThat(events).extracting(ChatEvent::type).containsExactly(
                "runtime.thinking",
                "runtime.thinking",
                "runtime.thinking",
                "message.delta"
        );
        assertThat(events.get(1).payload()).containsEntry("text", "分析过程");
        assertThat(events.get(3).payload()).containsEntry("delta", "最终答案");
    }

    @Test
    void keepsThinkStateAcrossChunks() {
        LegacySkillResponseNormalizer.LegacySkillStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"content\":\"<think>分析\"}", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "message: {\"content\":\"过程</think>答案\",\"endFlag\":true}", state);

        assertThat(first).extracting(ChatEvent::type).containsExactly("runtime.thinking", "runtime.thinking");
        assertThat(second).extracting(ChatEvent::type).containsExactly(
                "runtime.thinking",
                "runtime.thinking",
                "message.delta",
                "message.completed"
        );
        assertThat(first.get(1).payload()).containsEntry("text", "分析");
        assertThat(second.get(0).payload()).containsEntry("text", "过程");
        assertThat(second.get(2).payload()).containsEntry("delta", "答案");
    }
}
