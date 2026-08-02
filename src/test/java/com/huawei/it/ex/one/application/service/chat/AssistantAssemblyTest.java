package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import org.junit.jupiter.api.Test;

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
    void placeholderPolicyDropsBusinessContentAndKeepsInteractionControls() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER);
        AssistantAssembly assembly = new AssistantAssembly(state);

        assembly.observe(com.huawei.it.ex.one.domain.chat.MessageDeltaEvent.of(
                "run1", "session1", "真实回答"));
        assembly.observe(RuntimeEvent.thinking("run1", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "thinking",
                "text", "真实思考过程"
        )));
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
}
