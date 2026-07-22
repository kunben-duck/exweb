package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void routeSingleWithoutUsableAccessNameIsProtocolFailure() throws Exception {
        IntentRecognitionResult missingResult = mapper("ex_").toRecognitionResult(response("intent-1", null));
        IntentRecognitionResult prefixOnlyResult = mapper("ex_").toRecognitionResult(response("intent-1", "ex_"));
        IntentDecision missing = missingResult.decision();
        IntentDecision prefixOnly = prefixOnlyResult.decision();

        assertThat(missingResult.status()).isEqualTo(IntentRecognitionResult.Status.FAILED_OR_DEGRADED);
        assertThat(prefixOnlyResult.status()).isEqualTo(IntentRecognitionResult.Status.FAILED_OR_DEGRADED);
        assertThat(missing.intentCode()).isEqualTo("finance.runtime.intent_error");
        assertThat(missing.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(missing.simpleTask()).isFalse();
        assertThat(missing.candidateDomainAgentId()).isNull();
        assertThat(prefixOnly.complexity()).isEqualTo(TaskComplexity.COMPLEX);
        assertThat(prefixOnly.simpleTask()).isFalse();
        assertThat(prefixOnly.candidateDomainAgentId()).isNull();
        assertThat(prefixOnly.slots()).containsEntry("accessName", "ex_");
        assertThat(prefixOnly.slots()).containsEntry("intentId", "intent-1")
                .containsEntry("resourceId", "diagnostic-resource");
        assertThat(prefixOnly.raw()).containsEntry("reason",
                "ROUTE_SINGLE accessName missing after normalization");
    }

    @Test
    void routeMultiPreservesOrderedDistinctCandidateIntentNames() throws Exception {
        IntentRecognitionResult result = mapper("").toRecognitionResult(objectMapper.readTree("""
                {
                  "code": 200,
                  "status": "success",
                  "data": {
                    "result": {
                      "routeAction": "ROUTE_MULTI",
                      "items": [
                        {"intentName": "  财经智能问数  "},
                        {"intentName": ""},
                        {"intentName": "财经智能问数"},
                        {"intentName": null},
                        {"intentName": "财经知识助手"}
                      ]
                    }
                  }
                }
                """));

        assertThat(result.status()).isEqualTo(IntentRecognitionResult.Status.FINAL);
        assertThat(result.decision().intentName()).isEqualTo("多意图命中，进入 Relay Runtime");
        assertThat(result.decision().slots())
                .containsEntry("routeAction", "ROUTE_MULTI")
                .containsEntry("candidateIntentNames", List.of("财经智能问数", "财经知识助手"));
    }

    @Test
    void noMatchUsesDefaultAgentDisplayName() throws Exception {
        IntentDecision decision = mapper("").toDecision(noMatchResponse());

        assertThat(decision.intentCode()).isEqualTo("finance.runtime.no_intent");
        assertThat(decision.intentName())
                .isEqualTo("未识别到可用意图，进入 FIN Supervisor Agent");
        assertThat(decision.slots()).containsEntry("routeAction", "NO_MATCH");
        assertThat(decision.candidateDomainAgentId()).isNull();
    }

    @Test
    void noMatchUsesTrimmedConfiguredAgentDisplayName() throws Exception {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setNoMatchAgentName("  财务总控 Agent  ");

        IntentDecision decision = mapper(properties).toDecision(noMatchResponse());

        assertThat(decision.intentName()).isEqualTo("未识别到可用意图，进入 财务总控 Agent");
    }

    @Test
    void noMatchFallsBackToDefaultAgentDisplayNameForMissingOrBlankConfiguration() throws Exception {
        for (String configuredName : new String[]{null, "", "   "}) {
            IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
            properties.setNoMatchAgentName(configuredName);

            IntentDecision decision = mapper(properties).toDecision(noMatchResponse());

            assertThat(decision.intentName())
                    .isEqualTo("未识别到可用意图，进入 FIN Supervisor Agent");
        }
    }

    private IntentServiceResponseMapper mapper(String prefix) {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setResponseAccessNamePrefix(prefix);
        return mapper(properties);
    }

    private IntentServiceResponseMapper mapper(IntentServiceHttpProperties properties) {
        return new IntentServiceResponseMapper(objectMapper, properties);
    }

    private com.fasterxml.jackson.databind.JsonNode noMatchResponse() throws Exception {
        return objectMapper.readTree("""
                {
                  "code": 200,
                  "status": "success",
                  "data": {
                    "result": {
                      "routeAction": "NO_MATCH",
                      "items": [],
                      "clarification": null
                    }
                  }
                }
                """);
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
