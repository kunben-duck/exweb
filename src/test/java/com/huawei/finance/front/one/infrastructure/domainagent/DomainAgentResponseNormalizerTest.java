package com.huawei.finance.front.one.infrastructure.domainagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import java.util.List;
import org.junit.jupiter.api.Test;

class DomainAgentResponseNormalizerTest {
    private final DomainAgentResponseNormalizer normalizer = new DomainAgentResponseNormalizer(new ObjectMapper());

    @Test
    void mapsDomainAgentEventStreamFramesToChatServiceEvents() {
        String chunk = """
                message: {"traceId":"trace-1"}

                message: {"sessionId":"domain-session-1"}

                message: {"messageId":"domain-message-1"}

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
                "runtime.progress",
                "message.delta",
                "message.completed"
        );
        assertThat(events.get(0).payload()).containsEntry("metadataType", "trace")
                .containsEntry("traceId", "trace-1");
        assertThat(events.get(1).payload()).containsEntry("metadataType", "domain_agent_session")
                .containsEntry("domainAgentSessionId", "domain-session-1");
        assertThat(events.get(2).payload()).containsEntry("metadataType", "domain_agent_message")
                .containsEntry("domainAgentMessageId", "domain-message-1");
        assertThat(events.get(3).payload()).containsEntry("cardUrl", "https://card")
                .containsEntry("sourceType", "cardUrl")
                .containsEntry("cardType", "url")
                .containsEntry("intent", "tax")
                .containsEntry("domainAgentId", "skill-tax");
        assertThat(events.get(5).payload()).containsEntry("delta", "你好");
        assertThat(events.get(6).payload()).containsEntry("sourceType", "domain-agent-end");
    }

    @Test
    void mapsStandaloneIntentAndSkillToMetadata() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1",
                "message: {\"intent\":\"CreditSales\",\"skillId\":\"skill-credit\"}");

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().type()).isEqualTo("runtime.metadata");
        assertThat(events.getFirst().payload()).containsEntry("metadataType", "domain_agent")
                .containsEntry("intent", "CreditSales")
                .containsEntry("domainAgentId", "skill-credit");
    }

    @Test
    void unknownJsonFallsBackToRuntimeEvent() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", "message: {\"foo\":\"bar\"}");

        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("runtime.event");
        assertThat(events.get(0).payload()).containsEntry("source", "domain-agent");
    }

    @Test
    void supportsDomainAgentMessagePrefixesAndRedactsSensitiveUnknownPayload() {
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

                message: {"diyCardScene":{"type":"tax"}}

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
        assertThat(events.get(4).payload()).containsEntry("sourceType", "diyCardScene")
                .containsEntry("cardType", "diyCardScene")
                .containsEntry("cardSources", List.of("diyCardScene"));
    }

    @Test
    void streamsSplitDiyCardSceneWithoutInvalidJson() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"diyCardScene\":{\"title\":\"税务", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "意见\",\"items\":[{\"name\":\"A\"}]}}", state);

        assertThat(first).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.card", "runtime.card");
        assertThat(first.getFirst().payload()).containsEntry("sourceType", "diyCardScene")
                .containsEntry("contentType", "application/json")
                .containsEntry("fragment", true)
                .containsEntry("complete", false);
        assertThat(second.getLast().payload()).containsEntry("sourceType", "diyCardScene")
                .containsEntry("fragment", true)
                .containsEntry("complete", true);
        assertThat(second).extracting(ChatEvent::type).doesNotContain("runtime.event");
    }

    @Test
    void streamsSplitReferencesWithoutRequiringFieldNameInLaterChunks() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"sourcesDocuments\":[{\"docName\":\"制度", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "文件\",\"url\":\"https://example.com/a.pdf\"}]}", state);

        assertThat(first).extracting(ChatEvent::type).containsExactly("runtime.reference");
        assertThat(second).extracting(ChatEvent::type)
                .containsExactly("runtime.reference", "runtime.reference");
        assertThat(first.getFirst().payload()).containsEntry("sourceType", "sourcesDocuments")
                .containsEntry("fragment", true)
                .containsEntry("complete", false);
        assertThat(second.getFirst().payload()).containsEntry("sourceType", "sourcesDocuments");
        assertThat(second.getLast().payload()).containsEntry("sourceType", "sourcesDocuments")
                .containsEntry("fragment", true)
                .containsEntry("complete", true);
    }

    @Test
    void streamsSplitSearchListWithoutInvalidJson() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"searchList\":[{\"title\":\"网页", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "资料\",\"url\":\"https://example.com\"}]}", state);

        assertThat(first).extracting(ChatEvent::type).containsExactly("runtime.reference");
        assertThat(second).extracting(ChatEvent::type)
                .containsExactly("runtime.reference", "runtime.reference");
        assertThat(first.getFirst().payload()).containsEntry("sourceType", "searchList");
        assertThat(second.getLast().payload()).containsEntry("sourceType", "searchList")
                .containsEntry("fragment", true)
                .containsEntry("complete", true);
    }

    @Test
    void streamsSplitProcessResultAsProgressFragments() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"processResult\":{\"dynamicResponse\":[{\"title\":\"工具", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "调用\",\"type\":\"str\"}]}}", state);

        assertThat(first).extracting(ChatEvent::type).containsExactly("runtime.progress");
        assertThat(second).extracting(ChatEvent::type)
                .containsExactly("runtime.progress", "runtime.progress");
        assertThat(first.getFirst().payload()).containsEntry("sourceType", "processResult")
                .containsEntry("fragment", true)
                .containsEntry("complete", false);
        assertThat(second.getLast().payload()).containsEntry("sourceType", "processResult")
                .containsEntry("fragment", true)
                .containsEntry("complete", true);
    }

    @Test
    void skipsNullItemsInStructuredArrays() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"sourcesDocuments":[null,{"docName":"a.pdf"}],"cardList":[null,{"title":"卡片"}]}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.reference", "runtime.card");
        assertThat(events.get(0).payload().toString()).contains("a.pdf").doesNotContain("null");
        assertThat(events.get(1).payload().toString()).contains("卡片").doesNotContain("null");
        assertThat(events.get(1).payload()).containsEntry("sourceType", "cardList")
                .containsEntry("cardType", "cardList");
    }

    @Test
    void keepsOriginalSourceTypeForSingleDiyCardScene() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"diyCardScene":{"type":"tax"}}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "diyCardScene")
                .containsEntry("cardType", "diyCardScene")
                .containsEntry("cardSources", List.of("diyCardScene"));
    }

    @Test
    void mapsOpenCardToRuntimeCard() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"openCard":"N","intent":"CreditSales","skillId":"skill-credit"}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "openCard")
                .containsEntry("cardType", "openCard")
                .containsEntry("cardSources", List.of("openCard"))
                .containsEntry("openCard", "N")
                .containsEntry("intent", "CreditSales")
                .containsEntry("domainAgentId", "skill-credit");
    }

    @Test
    void keepsDefensiveMixedCardMappingForUnexpectedCombinedFrame() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"diyCardScene":{"type":"tax"},"cardList":[{"title":"卡片"}]}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "domain-agent-card")
                .containsEntry("cardType", "mixed")
                .containsEntry("cardSources", List.of("diyCardScene", "cardList"));
    }

    @Test
    void keepsDefensiveMixedCardMappingWhenOpenCardCombinesWithOtherCardFields() {
        List<ChatEvent> events = normalizer.normalize("run1", "session1", """
                message: {"cardUrl":"https://card","openCard":"N"}
                """);

        assertThat(events).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(events.getFirst().payload()).containsEntry("sourceType", "domain-agent-card")
                .containsEntry("cardType", "mixed")
                .containsEntry("cardSources", List.of("cardUrl", "openCard"))
                .containsEntry("cardUrl", "https://card")
                .containsEntry("openCard", "N");
    }

    @Test
    void streamsSplitOpenCardWithoutInvalidJson() {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

        List<ChatEvent> first = normalizer.normalize("run1", "session1",
                "message: {\"openCard\":\"", state);
        List<ChatEvent> second = normalizer.normalize("run1", "session1",
                "N\"}", state);

        assertThat(first).extracting(ChatEvent::type).containsExactly("runtime.card");
        assertThat(second).extracting(ChatEvent::type).containsExactly("runtime.card", "runtime.card");
        assertThat(first.getFirst().payload()).containsEntry("sourceType", "openCard")
                .containsEntry("fragment", true)
                .containsEntry("complete", false);
        assertThat(second.getLast().payload()).containsEntry("sourceType", "openCard")
                .containsEntry("fragment", true)
                .containsEntry("complete", true);
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
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();

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
