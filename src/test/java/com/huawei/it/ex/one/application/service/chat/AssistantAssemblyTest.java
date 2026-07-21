package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AssistantAssemblyTest {

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
}
