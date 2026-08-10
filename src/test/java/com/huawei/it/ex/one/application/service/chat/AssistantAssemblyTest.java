package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceMetadata;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistencePolicy;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.chat.MessageSnapshotEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.lang.reflect.Field;
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
    void interactionControlResponseIsNotResourceLimitPartialOutput() {
        List<RuntimeEvent> controlResponses = List.of(
                RuntimeEvent.card("run-b", "session1", Map.of(
                        "source", "chatservice",
                        "sourceType", "intent-clarification-response",
                        "interactionType", "INTENT_CLARIFICATION",
                        "clarificationType", "AMBIGUOUS_ROUTE",
                        "answerText", "财经知识助手"
                )),
                RuntimeEvent.card("run-b", "session1", Map.of(
                        "source", "chatservice",
                        "sourceType", "clarification-response",
                        "interactionType", "AGENT_CLARIFICATION",
                        "answerText", "确认"
                )),
                RuntimeEvent.card("run-b", "session1", Map.of(
                        "source", "chatservice",
                        "sourceType", "route-switch-confirmation-response",
                        "interactionType", "ROUTE_SWITCH_CONFIRMATION",
                        "approved", true
                )));

        controlResponses.forEach(event -> {
            AssistantAssembly assembly = new AssistantAssembly();
            assembly.observe(event);
            assertThat(assembly.shouldPersistMessage()).isTrue();
            assertThat(assembly.hasResourceLimitPartialOutput()).isFalse();
        });
    }

    @Test
    void retainedBusinessOutputIsResourceLimitPartialOutput() {
        AssistantAssembly bodyAssembly = new AssistantAssembly();
        AssistantAssembly cardAssembly = new AssistantAssembly();

        bodyAssembly.observe(MessageDeltaEvent.of("run-b", "session1", "部分回答"));
        cardAssembly.observe(RuntimeEvent.card("run-b", "session1", Map.of(
                "source", "domain-agent",
                "sourceType", "diyCardScene",
                "diyCardScene", Map.of("type", "tax")
        )));

        assertThat(bodyAssembly.hasResourceLimitPartialOutput()).isTrue();
        assertThat(cardAssembly.hasResourceLimitPartialOutput()).isTrue();
    }

    @Test
    void placeholderResourceLimitRequiresObservedBusinessOutput() {
        AgentDataPersistenceState state = new AgentDataPersistenceState("回答已隐藏")
                .tighten(AgentDataPersistencePolicy.ASSISTANT_PLACEHOLDER)
                .markRuntimeDispatchStarted();
        AssistantAssembly controlOnly = new AssistantAssembly(state);
        AssistantAssembly withBusinessOutput = new AssistantAssembly(state);

        controlOnly.observe(RuntimeEvent.card("run-b", "session1", Map.of(
                "source", "chatservice",
                "sourceType", "clarification-response",
                "interactionType", "AGENT_CLARIFICATION",
                "answerText", "确认"
        )));
        withBusinessOutput.observe(MessageDeltaEvent.of("run-b", "session1", "真实回答"));

        assertThat(controlOnly.shouldPersistMessage()).isTrue();
        assertThat(controlOnly.hasResourceLimitPartialOutput()).isFalse();
        assertThat(withBusinessOutput.hasResourceLimitPartialOutput()).isTrue();
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
    void filtersProcessPartsAfterProcessSubBudgetWithoutChangingEssentialPart() {
        RuntimeStreamLimitsProperties properties = limits(4, 25, DataSize.ofMegabytes(1).toBytes());
        AssistantAssemblyBudgetRegistry registry = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        AssistantAssembly assembly = boundedAssembly("run1", registry);

        AssistantAssembly.ObservationResult first = assembly.observe(RuntimeEvent.progress(
                "run1", "session1", Map.of("sourceType", "progress-1", "text", "one")));
        AssistantAssembly.ObservationResult filtered = assembly.observe(RuntimeEvent.thinking(
                "run1", "session1", Map.of("sourceType", "thinking-2", "text", "two")));
        AssistantAssembly.ObservationResult card = assembly.observe(RuntimeEvent.card(
                "run1", "session1", Map.of("sourceType", "business-card", "title", "result")));

        assertThat(first.status()).isEqualTo(AssistantAssembly.Status.ACCEPTED);
        assertThat(filtered.status()).isEqualTo(AssistantAssembly.Status.FILTERED);
        assertThat(card.status()).isEqualTo(AssistantAssembly.Status.ACCEPTED);
        assertThat(assembly.parts()).extracting(part -> part.partType())
                .containsExactly("PROGRESS", "CARD");
        assembly.close();
        assertThat(registry.activeParts()).isZero();
        assertThat(registry.activeBytes()).isZero();
    }

    @Test
    void evictsOldestProcessPartToKeepEssentialPart() {
        RuntimeStreamLimitsProperties properties = limits(2, 100, DataSize.ofMegabytes(1).toBytes());
        AssistantAssemblyBudgetRegistry registry = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        AssistantAssembly assembly = boundedAssembly("run1", registry);

        assembly.observe(RuntimeEvent.progress(
                "run1", "session1", Map.of("sourceType", "old-progress", "text", "old")));
        assembly.observe(RuntimeEvent.thinking(
                "run1", "session1", Map.of("sourceType", "recent-thinking", "text", "recent")));
        AssistantAssembly.ObservationResult result = assembly.observe(RuntimeEvent.card(
                "run1", "session1", Map.of("sourceType", "business-card", "title", "result")));

        assertThat(result.status()).isEqualTo(AssistantAssembly.Status.ACCEPTED);
        assertThat(assembly.parts()).extracting(part -> part.partType())
                .containsExactly("THINKING", "CARD");
        assembly.close();
    }

    @Test
    void keepsUnicodeSafeBodyPrefixAndReleasesInstanceBudgetOnClose() {
        RuntimeStreamLimitsProperties properties = limits(10, 100, 4L);
        AssistantAssemblyBudgetRegistry registry = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        AssistantAssembly first = boundedAssembly("run1", registry);

        AssistantAssembly.ObservationResult overflow = first.observe(
                MessageDeltaEvent.of("run1", "session1", "你a🙂"));

        assertThat(overflow.status()).isEqualTo(AssistantAssembly.Status.ESSENTIAL_OVERFLOW);
        assertThat(overflow.limitType()).isEqualTo(
                com.huawei.it.ex.one.application.service.runtime.RuntimeStreamLimitType.ASSISTANT_BYTES);
        assertThat(first.finalContent()).isEqualTo("你a");
        assertThat(registry.activeBytes()).isEqualTo(4L);
        first.close();
        assertThat(registry.activeBytes()).isZero();

        AssistantAssembly second = boundedAssembly("run2", registry);
        assertThat(second.observe(MessageDeltaEvent.of("run2", "session1", "test")).status())
                .isEqualTo(AssistantAssembly.Status.ACCEPTED);
        assertThat(second.finalContent()).isEqualTo("test");
        second.close();
    }

    @Test
    void snapshotReleasesReplacedDeltaDraftBudget() {
        RuntimeStreamLimitsProperties properties = limits(10, 100, DataSize.ofMegabytes(1).toBytes());
        AssistantAssemblyBudgetRegistry registry = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        AssistantAssembly assembly = boundedAssembly("run1", registry);

        assembly.observe(MessageDeltaEvent.of("run1", "session1", "旧草稿内容"));
        AssistantAssembly.ObservationResult result = assembly.observe(
                MessageSnapshotEvent.of("run1", "session1", "最终正文"));

        assertThat(result.status()).isEqualTo(AssistantAssembly.Status.ACCEPTED);
        assertThat(assembly.finalContent()).isEqualTo("最终正文");
        assertThat(assembly.parts()).singleElement()
                .extracting(part -> part.partType())
                .isEqualTo("MESSAGE_SNAPSHOT");
        long expectedBytes = registry.textBytes("最终正文")
                + registry.serializedBytes(assembly.parts().getFirst());
        assertThat(registry.activeBytes()).isEqualTo(expectedBytes);
        assembly.close();
        assertThat(registry.activeBytes()).isZero();
    }

    @Test
    void snapshotDetachesLargeDeltaBuilderCapacity() throws Exception {
        RuntimeStreamLimitsProperties properties = limits(
                10, 100, DataSize.ofMegabytes(4).toBytes());
        AssistantAssemblyBudgetRegistry registry = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        AssistantAssembly assembly = boundedAssembly("run1", registry);
        String largeDelta = "x".repeat(1_000_000);

        assembly.observe(MessageDeltaEvent.of("run1", "session1", largeDelta));
        int expandedCapacity = deltaDraft(assembly).capacity();
        assembly.observe(MessageSnapshotEvent.of("run1", "session1", "最终正文"));

        assertThat(expandedCapacity).isGreaterThanOrEqualTo(largeDelta.length());
        assertThat(deltaDraft(assembly).capacity()).isLessThan(1_024);
        assembly.close();
    }

    @Test
    void releasesSharedInstancePartBudgetForAnotherRun() {
        RuntimeStreamLimitsProperties properties = limits(2, 100, DataSize.ofMegabytes(1).toBytes());
        properties.setAssistantMaxActivePartsPerInstance(2);
        AssistantAssemblyBudgetRegistry registry = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        AssistantAssembly first = boundedAssembly("run1", registry);
        AssistantAssembly second = boundedAssembly("run2", registry);

        first.observe(RuntimeEvent.card("run1", "session1", Map.of("sourceType", "card-a")));
        second.observe(RuntimeEvent.card("run2", "session1", Map.of("sourceType", "card-b")));
        AssistantAssembly.ObservationResult overflow = second.observe(
                RuntimeEvent.card("run2", "session1", Map.of("sourceType", "card-c")));
        assertThat(overflow.status()).isEqualTo(AssistantAssembly.Status.ESSENTIAL_OVERFLOW);

        first.close();
        assertThat(second.observe(RuntimeEvent.card(
                "run2", "session1", Map.of("sourceType", "card-d"))).status())
                .isEqualTo(AssistantAssembly.Status.ACCEPTED);
        second.close();
        assertThat(registry.activeParts()).isZero();
    }

    @Test
    void stopSealRejectsLateProjectionMutationAndKeepsBudgetUntilClose() {
        RuntimeStreamLimitsProperties properties = limits(10, 100, DataSize.ofMegabytes(1).toBytes());
        AssistantAssemblyBudgetRegistry registry = new AssistantAssemblyBudgetRegistry(
                properties, new ObjectMapper());
        AssistantAssembly assembly = boundedAssembly("run1", registry);

        assembly.observe(MessageDeltaEvent.of("run1", "session1", "已接收正文"));
        long retainedBytes = registry.activeBytes();
        assembly.sealForStop();

        AssistantAssembly.ObservationResult late = assembly.observe(
                MessageDeltaEvent.of("run1", "session1", "迟到正文"));

        assertThat(late.status()).isEqualTo(AssistantAssembly.Status.ACCEPTED);
        assertThat(assembly.finalContent()).isEqualTo("已接收正文");
        assertThat(registry.activeBytes()).isEqualTo(retainedBytes);
        assembly.close();
        assertThat(registry.activeBytes()).isZero();
    }

    private AssistantAssembly boundedAssembly(String runId, AssistantAssemblyBudgetRegistry registry) {
        return new AssistantAssembly(AgentDataPersistenceState.full(), registry, registry.open(runId));
    }

    private StringBuilder deltaDraft(AssistantAssembly assembly) throws Exception {
        Field field = AssistantAssembly.class.getDeclaredField("deltaDraft");
        field.setAccessible(true);
        return (StringBuilder) field.get(assembly);
    }

    private RuntimeStreamLimitsProperties limits(int parts, int processRatio, long bytes) {
        RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
        properties.setAssistantMaxPartsPerRun(parts);
        properties.setAssistantMaxActivePartsPerInstance(Math.max(parts, 10));
        properties.setAssistantMaxBytesPerRun(DataSize.ofBytes(bytes));
        properties.setAssistantMaxActiveBytesPerInstance(DataSize.ofBytes(Math.max(bytes, 1_024L)));
        properties.setAssistantProcessMaxRatio(processRatio);
        return properties;
    }
}
