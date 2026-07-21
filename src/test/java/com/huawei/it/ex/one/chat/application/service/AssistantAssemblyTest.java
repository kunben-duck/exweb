package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.application.model.AssistantAssembly;
import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.common.event.RuntimeEvent;
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
}
