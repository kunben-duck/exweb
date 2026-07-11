package com.huawei.finance.front.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import java.util.Map;
import org.junit.jupiter.api.Test;

class IntentServiceResponseMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void stripsConfiguredAccessNamePrefixAndPreservesIntentIdentity() throws Exception {
        IntentDecision decision = mapper("ex_").toDecision(response("intent-1", "  ex_skill-1  "));

        assertThat(decision.intentCode()).isEqualTo("intent-1");
        assertThat(decision.candidateDomainAgentId()).isEqualTo("skill-1");
        assertThat(decision.slots()).containsEntry("intentId", "intent-1")
                .containsEntry("accessName", "skill-1")
                .containsEntry("resourceId", "diagnostic-resource");
        assertThat(selectedItem(decision)).containsEntry("accessName", "  ex_skill-1  ");
    }

    @Test
    void usesOriginalAccessNameWhenPrefixIsNotConfigured() throws Exception {
        IntentDecision decision = mapper("").toDecision(response("intent-1", "skill-1"));

        assertThat(decision.candidateDomainAgentId()).isEqualTo("skill-1");
    }

    @Test
    void keepsOriginalAccessNameWhenConfiguredPrefixDoesNotMatch() throws Exception {
        IntentDecision decision = mapper("ex_").toDecision(response("intent-1", "other_skill-1"));

        assertThat(decision.candidateDomainAgentId()).isEqualTo("other_skill-1");
    }

    @Test
    void routeSingleWithoutUsableAccessNameFallsBackToRelay() throws Exception {
        IntentDecision missing = mapper("ex_").toDecision(response("intent-1", null));
        IntentDecision prefixOnly = mapper("ex_").toDecision(response("intent-1", "ex_"));

        assertThat(missing.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(missing.simpleTask()).isFalse();
        assertThat(missing.candidateDomainAgentId()).isNull();
        assertThat(prefixOnly.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(prefixOnly.simpleTask()).isFalse();
        assertThat(prefixOnly.candidateDomainAgentId()).isNull();
        assertThat(prefixOnly.slots()).doesNotContainKey("accessName");
        assertThat(prefixOnly.slots()).containsEntry("intentId", "intent-1")
                .containsEntry("resourceId", "diagnostic-resource");
    }

    private IntentServiceResponseMapper mapper(String prefix) {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setResponseAccessNamePrefix(prefix);
        return new IntentServiceResponseMapper(objectMapper, properties);
    }

    private com.fasterxml.jackson.databind.JsonNode response(String intentId, String accessName) {
        var item = objectMapper.createObjectNode();
        item.put("intentId", intentId);
        item.put("intentName", "测试意图");
        if (accessName != null) {
            item.put("accessName", accessName);
        }
        item.put("confidence", 0.95);
        item.putObject("resourceInstruction").put("resourceId", "diagnostic-resource");
        var root = objectMapper.createObjectNode();
        root.put("code", 200);
        root.put("status", "success");
        var result = root.putObject("data").putObject("result");
        result.put("routeAction", "ROUTE_SINGLE");
        result.putArray("items").add(item);
        return root;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> selectedItem(IntentDecision decision) {
        return (Map<String, Object>) decision.raw().get("selectedItem");
    }
}
