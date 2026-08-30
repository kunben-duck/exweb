package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

class AssistantAssemblyTest {

    @Test
    void keepsIntentProcessEventsOutOfHistoricalParts() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(RuntimeEvent.progress("run1", "session1", Map.of(
                "source", "intent-agent",
                "sourceType", "intent-start",
                "message", "正在识别问题意图"
        )));
        assembly.observe(RuntimeEvent.progress("run1", "session1", Map.of(
                "source", "intent-agent",
                "sourceType", "intent-progress",
                "message", "ES检索中"
        )));
        assembly.observe(RuntimeEvent.thinking("run1", "session1", Map.of(
                "source", "intent-agent",
                "sourceType", "intent-delta",
                "text", "处理中"
        )));

        assertThat(assembly.parts()).isEmpty();
        assertThat(assembly.shouldPersistMessage()).isFalse();
    }

    @Test
    void keepsAmbiguousRouteSelectionResponseInHistoricalParts() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(RuntimeEvent.card("run-b", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "intent-clarification-response",
                "interactionType", "INTENT_CLARIFICATION",
                "clarificationType", "AMBIGUOUS_ROUTE",
                "interactionId", "interaction-1",
                "assistantMessageId", "message-assistant",
                "sourceRunId", "run-a",
                "selectionSource", "USER",
                "answerText", "财经知识助手"
        )));

        assertThat(assembly.parts()).singleElement().satisfies(part -> {
            assertThat(part.partType()).isEqualTo("INTENT_CLARIFICATION_RESPONSE");
            assertThat(part.contentText()).isEqualTo("财经知识助手");
            assertThat(part.payload())
                    .containsEntry("assistantMessageId", "message-assistant")
                    .containsEntry("sourceRunId", "run-a")
                    .containsEntry("selectionSource", "USER");
        });
    }

    @Test
    void preservesCompleteDomainAgentProcessResultInHistoricalPart() {
        String fixedResponse = "<svg>" + "x".repeat(4079) + "</svg>";
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(RuntimeEvent.progress("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "processResult",
                "processResult", Map.of("fixedResponse", fixedResponse)
        )));

        assertThat(assembly.parts()).singleElement().satisfies(part -> {
            assertThat(part.partType()).isEqualTo("PROGRESS");
            assertThat(part.payload()).containsKey("serverTimestampMs");
            assertThat(part.payload().get("processResult")).isInstanceOf(Map.class);
            Map<?, ?> processResult = (Map<?, ?>) part.payload().get("processResult");
            assertThat(processResult.get("fixedResponse")).isEqualTo(fixedResponse);
        });
    }

    @Test
    void keepsSearchListMetadataInHistoricalReferencePart() {
        Map<String, Object> metadata = Map.of(
                "knowLevel", List.of("MIP", "CIP", "IIP"),
                "knowMapping", List.of(Map.of("type", "MIP", "name", "作业依据"))
        );
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(RuntimeEvent.reference("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "searchList",
                "referenceType", "search_list",
                "references", List.of(Map.of("title", "任命通知（子公司CFO）")),
                "metadata", metadata
        )));

        assertThat(assembly.parts()).singleElement().satisfies(part -> {
            assertThat(part.partType()).isEqualTo("REFERENCE");
            assertThat(part.sourceType()).isEqualTo("searchList");
            assertThat(part.payload())
                    .containsEntry("metadata", metadata)
                    .containsKey("serverTimestampMs");
        });
    }

    @Test
    void preservesNoMatchAgentDisplayNameInHistoricalPart() {
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(RuntimeEvent.progress("run1", "session1", Map.of(
                "source", "intent-agent",
                "sourceType", "intent-result",
                "message", "已完成意图识别",
                "routeAction", "NO_MATCH",
                "intentName", "未识别到可用意图，进入 FIN Supervisor Agent"
        )));

        assertThat(assembly.parts()).singleElement().satisfies(part -> {
            assertThat(part.partType()).isEqualTo("PROGRESS");
            assertThat(part.payload())
                    .containsEntry("sourceType", "intent-result")
                    .containsEntry("intentName", "未识别到可用意图，进入 FIN Supervisor Agent");
        });
    }

    @Test
    void mapsSpecificSceneInfoCardToUserVisibleHistoricalPart() {
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "specificSceneInfo",
                "cardType", "specificSceneInfo",
                "cardSources", List.of("specificSceneInfo"),
                "specificSceneInfo", List.of(Map.of("type", "authorization"))
        )));

        assertThat(assembly.shouldPersistMessage()).isTrue();
        assertThat(assembly.parts()).singleElement().satisfies(part -> {
            assertThat(part.partType()).isEqualTo("CARD");
            assertThat(part.sourceType()).isEqualTo("specificSceneInfo");
            assertThat(part.payload())
                    .containsEntry("cardType", "specificSceneInfo")
                    .containsKey("serverTimestampMs");
        });
    }

    @Test
    void keepsRecommendedQuestionsInOpenCardHistoricalPart() {
        List<Map<String, Object>> recommendedQuestions = List.of(
                Map.of("query", "请展开下一个印章的审核结果？", "id", 1, "metadata", Map.of()),
                Map.of("query", "请展开下一个文件的审核结果？", "id", 2, "metadata", Map.of())
        );
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "openCard",
                "cardType", "openCard",
                "cardSources", List.of("openCard"),
                "openCard", "Y",
                "recommendedQuestions", recommendedQuestions
        )));

        assertThat(assembly.parts()).singleElement().satisfies(part -> {
            assertThat(part.partType()).isEqualTo("CARD");
            assertThat(part.sourceType()).isEqualTo("openCard");
            assertThat(part.payload())
                    .containsEntry("openCard", "Y")
                    .containsEntry("recommendedQuestions", recommendedQuestions)
                    .containsKey("serverTimestampMs");
        });
    }

    @Test
    void keepsDiyCardSceneContentAgentInHistoricalPart() {
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "diyCardScene",
                "cardType", "diyCardScene",
                "cardSources", List.of("diyCardScene"),
                "diyCardScene", Map.of("type", "tax"),
                "contentAgent", "卡片补充内容"
        )));

        assertThat(assembly.parts()).singleElement().satisfies(part -> {
            assertThat(part.partType()).isEqualTo("CARD");
            assertThat(part.sourceType()).isEqualTo("diyCardScene");
            assertThat(part.payload()).containsEntry("contentAgent", "卡片补充内容");
        });
    }

    @Test
    void mergesConsecutiveStandaloneContentAgentCardsIntoOneHistoricalPart() {
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "cardUrl",
                "cardType", "url",
                "cardSources", List.of("cardUrl"),
                "cardUrl", "https://card"
        )));
        assembly.observe(contentAgentCard("<think>"));
        assembly.observe(contentAgentCard(""));
        assembly.observe(contentAgentCard("简单的问候。</think>"));

        assertThat(assembly.finalContent()).isEmpty();
        assertThat(assembly.parts()).hasSize(2);
        assertThat(assembly.parts().get(0).sourceType()).isEqualTo("cardUrl");
        assertThat(assembly.parts().get(0).contentText()).isEqualTo("https://card");
        assertThat(assembly.parts().get(1)).satisfies(part -> {
            assertThat(part.partType()).isEqualTo("CARD");
            assertThat(part.sourceType()).isEqualTo("contentAgent");
            assertThat(part.contentText()).isNull();
            assertThat(part.payload())
                    .containsEntry("cardType", "contentAgent")
                    .containsEntry("cardSources", List.of("contentAgent"))
                    .containsEntry("contentAgent", "<think>简单的问候。</think>")
                    .containsKey("serverTimestampMs");
        });
    }

    @Test
    void startsANewContentAgentPartAfterAnotherStructuredDomainAgentCard() {
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(contentAgentCard("第一张卡片"));
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "cardList",
                "cardType", "cardList",
                "cardSources", List.of("cardList"),
                "cardList", List.of(Map.of("title", "第二张卡片"))
        )));
        assembly.observe(contentAgentCard("第二张卡片正文"));

        assertThat(assembly.parts()).extracting(part -> part.sourceType())
                .containsExactly("contentAgent", "cardList", "contentAgent");
        assertThat(assembly.parts()).extracting(part -> part.contentText())
                .containsExactly(null, "cardList", null);
        assertThat(assembly.parts().get(0).payload()).containsEntry("contentAgent", "第一张卡片");
        assertThat(assembly.parts().get(2).payload()).containsEntry("contentAgent", "第二张卡片正文");
    }

    @Test
    void startsANewContentAgentPartAfterDomainAgentRefusalReroute() {
        AssistantAssembly assembly = new AssistantAssembly();
        assembly.observe(contentAgentCard("Agent A 卡片内容"));
        assembly.observe(RuntimeEvent.metadata("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "agent.refusal",
                "metadataType", "domain_agent_control",
                "supervisorAction", "REROUTE",
                "type", "agent.refusal",
                "code", "FN-EX-CAHT-BIZ-DAG-001",
                "reason", "需要切换领域"
        )));
        assembly.observe(RuntimeEvent.metadata("run1", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-reroute",
                "metadataType", "domain_agent_reroute",
                "action", "AUTO_SWITCH"
        )));
        assembly.observe(contentAgentCard("Agent B 卡片内容"));

        assertThat(assembly.parts()).extracting(part -> part.partType())
                .containsExactly("CARD", "DOMAIN_AGENT_REFUSAL", "METADATA", "CARD");
        assertThat(assembly.parts()).extracting(part -> part.sourceType())
                .containsExactly("contentAgent", "agent.refusal", "domain-agent-reroute", "contentAgent");
        assertThat(assembly.parts().get(0).contentText()).isNull();
        assertThat(assembly.parts().get(0).payload()).containsEntry("contentAgent", "Agent A 卡片内容");
        assertThat(assembly.parts().get(3).contentText()).isNull();
        assertThat(assembly.parts().get(3).payload()).containsEntry("contentAgent", "Agent B 卡片内容");
    }

    @Test
    void separatesHistoricalDomainAgentContentAfterThinking() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(domainAgentContent("第一段回答"));
        assembly.observe(domainAgentThinking("content.think", "STARTED"));
        assembly.observe(domainAgentThinking("content.think", "STREAMING"));
        assembly.observe(domainAgentThinking("content.think", "COMPLETED"));
        assembly.observe(domainAgentContent("第二段回答"));

        assertThat(assembly.finalContent()).isEqualTo(
                "第一段回答" + AssistantAssembly.DOMAIN_AGENT_CONTENT_SEGMENT_MARKER + "第二段回答");
    }

    @Test
    void supportsStateAndDocumentedDomainAgentThinkingFrames() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(domainAgentContent("第一段"));
        assembly.observe(domainAgentThinking("state", "STARTED"));
        assembly.observe(domainAgentContent("第二段"));
        assembly.observe(domainAgentFallbackThinking(Map.of(
                "think_state", "start",
                "think_content", "分析过程")));
        assembly.observe(domainAgentContent("第三段"));
        assembly.observe(domainAgentFallbackThinking(Map.of(
                "thinkState", "stop",
                "thinkContent", "分析完成")));
        assembly.observe(domainAgentContent("第四段"));

        String marker = AssistantAssembly.DOMAIN_AGENT_CONTENT_SEGMENT_MARKER;
        assertThat(assembly.finalContent()).isEqualTo(
                "第一段" + marker + "第二段" + marker + "第三段" + marker + "第四段");
    }

    @Test
    void doesNotCreateEmptySegmentsAroundThinking() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(domainAgentThinking("content.think", "STARTED"));
        assembly.observe(domainAgentContent("第一段"));
        assembly.observe(domainAgentContent("连续正文"));
        assembly.observe(domainAgentThinking("content.think", "COMPLETED"));

        assertThat(assembly.finalContent()).isEqualTo("第一段连续正文");
    }

    @Test
    void ignoresNonDomainAgentThinkingForHistoricalSegmentation() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(domainAgentContent("第一段"));
        assembly.observe(RuntimeEvent.thinking("run1", "session1", Map.of(
                "source", "relay",
                "sourceType", "thinking-content-update",
                "text", "Relay思考")));
        assembly.observe(domainAgentContent("第二段"));

        assertThat(assembly.finalContent()).isEqualTo("第一段第二段");
    }

    @Test
    void resetsHistoricalSegmentationAfterDomainAgentRefusal() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(domainAgentContent("旧Agent回答"));
        assembly.observe(domainAgentThinking("content.think", "STARTED"));
        assembly.observe(RuntimeEvent.metadata("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "agent.refusal",
                "metadataType", "domain_agent_control",
                "supervisorAction", "REROUTE",
                "type", "agent.refusal",
                "code", "REFUSED",
                "reason", "切换技能")));
        assembly.observe(domainAgentContent("新Agent回答"));

        assertThat(assembly.finalContent()).isEqualTo("新Agent回答");
    }

    @Test
    void resetsHistoricalSegmentationForRouteSwitchConfirmation() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(domainAgentContent("旧Agent回答"));
        assembly.observe(domainAgentThinking("content.think", "STARTED"));
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "route-switch-confirmation-request",
                "message", "是否切换技能")));
        assembly.observe(domainAgentContent("新Agent回答"));

        assertThat(assembly.finalContent()).isEqualTo("新Agent回答");
    }

    @Test
    void keepsSnapshotAuthoritativeOverSegmentedDeltaHistory() {
        AssistantAssembly assembly = new AssistantAssembly();

        assembly.observe(domainAgentContent("第一段"));
        assembly.observe(domainAgentThinking("content.think", "COMPLETED"));
        assembly.observe(domainAgentContent("第二段"));
        assembly.observe(MessageSnapshotEvent.of("run1", "session1", "最终快照"));

        assertThat(assembly.finalContent()).isEqualTo("最终快照");
    }

    @Test
    void placeholderPolicyDropsBusinessContentAndKeepsInteractionControls() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);

        assembly.observe(domainAgentContent("真实回答"));
        assembly.observe(RuntimeEvent.thinking("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "thinking",
                "text", "真实思考过程"
        )));
        assembly.observe(domainAgentContent("第二段回答"));
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "intent-clarification-request",
                "clarifyQuestion", "请选择技能"
        )));

        assertThat(assembly.shouldPersistMessage()).isTrue();
        assertThat(assembly.finalContent()).isEqualTo("回答已隐藏");
        assertThat(assembly.appendAnswerPart()).isFalse();
        assertThat(assembly.parts()).singleElement()
                .extracting(part -> part.partType())
                .isEqualTo("INTENT_CLARIFICATION_REQUEST");
        assertThat(AgentDataPersistenceMetadata.placeholderAssistant(
                assembly.assistantMetadata(null))).isTrue();
    }

    @Test
    void placeholderPolicyDoesNotCreateAssistantWithoutPersistableOutput() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);

        assembly.observe(RuntimeEvent.metadata("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "session-ready"
        )));

        assertThat(assembly.shouldPersistMessage()).isFalse();
        assertThat(assembly.parts()).isEmpty();
    }

    @Test
    void placeholderPolicyDoesNotPersistContentAgentCardData() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);

        assembly.observe(contentAgentCard("真实卡片正文"));

        assertThat(assembly.shouldPersistMessage()).isTrue();
        assertThat(assembly.finalContent()).isEqualTo("回答已隐藏");
        assertThat(assembly.parts()).isEmpty();
    }

    @Test
    void placeholderPolicyCreatesAssistantAfterRuntimeDispatchStartsWithoutBusinessEvents() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER)
                .markRuntimeDispatchStarted();
        AssistantAssembly assembly = new AssistantAssembly(state);

        assertThat(assembly.shouldPersistMessage()).isTrue();
        assertThat(assembly.finalContent()).isEqualTo("回答已隐藏");
        assertThat(assembly.parts()).isEmpty();
    }

    @Test
    void runtimeDispatchMarkerRoundTripsOnlyInPrivateRunMetadata() {
        AgentDataPersistenceState original = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER)
                .markRuntimeDispatchStarted();

        AgentDataPersistenceState restored = AgentDataPersistenceState.fromRunMetadata(
                original.runMetadataOverlay(), null);

        assertThat(restored.placeholderMode()).isTrue();
        assertThat(restored.runtimeDispatchStarted()).isTrue();
        assertThat(restored.placeholderContent()).isEqualTo("回答已隐藏");
        assertThat(AgentDataPersistenceMetadata.removeRunPolicy(original.runMetadataOverlay()))
                .isEmpty();
    }

    @Test
    void placeholderPolicyKeepsAttachmentValidationControlParts() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);

        assembly.observe(RuntimeEvent.progress("run1", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
                "skillId", "skill-1",
                "stage", "attachment_validation",
                "status", "FAILED")));
        assembly.observe(RuntimeEvent.card("run1", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "domain-agent-attachment-validation",
                "code", "DOMAIN_AGENT_ATTACHMENT_TYPE_UNSUPPORTED",
                "skillId", "skill-1",
                "cardType", "domainAgentAttachmentUnsupported",
                "cardSources", List.of("attachmentValidation"))));

        assertThat(assembly.shouldPersistMessage()).isTrue();
        assertThat(assembly.finalContent()).isEqualTo("回答已隐藏");
        assertThat(assembly.parts()).extracting(part -> part.partType())
                .containsExactly("PROGRESS", "CARD");
        assertThat(assembly.parts()).allSatisfy(part ->
                assertThat(part.sourceType()).isEqualTo("domain-agent-attachment-validation"));
    }

    private RuntimeEvent contentAgentCard(String content) {
        return RuntimeEvent.card("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "contentAgent",
                "cardType", "contentAgent",
                "cardSources", List.of("contentAgent"),
                "contentAgent", content
        ));
    }

    private MessageDeltaEvent domainAgentContent(String content) {
        return new MessageDeltaEvent("run1", "session1", 0, Instant.now(), content, Map.of(
                "delta", content,
                "sourceType", "domain-agent-content"
        ));
    }

    private RuntimeEvent domainAgentThinking(String sourceType, String status) {
        return RuntimeEvent.thinking("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", sourceType,
                "status", status
        ));
    }

    private RuntimeEvent domainAgentFallbackThinking(Map<String, Object> frame) {
        return RuntimeEvent.fallback("run1", "session1", new RuntimeEvent.FallbackPayload(
                "domain-agent",
                "unknown",
                "event",
                "runtime",
                "debug",
                null,
                Map.of("sourcePayload", frame)
        ));
    }
}
