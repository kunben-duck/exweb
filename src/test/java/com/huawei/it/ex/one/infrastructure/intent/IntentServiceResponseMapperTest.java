/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import com.huawei.it.ex.one.application.config.IntentRecordProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

class IntentServiceResponseMapperTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void capturesRawAmbiguousResponseWithoutChangingPublicCandidates() throws Exception {
        JsonNode raw = ambiguousResponse();
        IntentRecordProperties records = new IntentRecordProperties();
        records.setEnabled(true);
        IntentServiceHttpProperties http = new IntentServiceHttpProperties();
        http.setResponseAccessNamePrefix("ex_");

        IntentRecognitionResult result = new IntentServiceResponseMapper(objectMapper, http, records)
                .toRecognitionResult(raw);

        assertThat(result.waitingClarification()).isTrue();
        assertThat(objectMapper.<JsonNode>valueToTree(result.auditRawResponse())).isEqualTo(raw);
        JsonNode publicPayload = objectMapper.valueToTree(result.clarificationPayload());
        assertThat(publicPayload.path("candidateIntents").get(0).path("skillId").asText()).isEqualTo("skill-b");
        assertThat(publicPayload.path("candidateIntents").get(1).path("skillId").asText()).isEqualTo("skill-a");
        assertThat(objectMapper.writeValueAsString(result)).doesNotContain("auditRawResponse", "audit-only-marker");
        assertThat(result.auditRawResponse()).containsEntry("optionalDetail", null);
        ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("auditOnly", "changed");
        assertThat(result.auditRawResponse()).containsEntry("auditOnly", "audit-only-marker");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void doesNotCopyWholeResponseWhenDisabledOrOrdinaryClarification(boolean enabled) throws Exception {
        JsonNode raw = ambiguousResponse();
        if (enabled) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) raw.path("data").path("result").path("clarification"))
                    .put("type", "MISSING_INFORMATION");
        }
        ObjectMapper copying = spy(objectMapper);
        IntentRecordProperties records = new IntentRecordProperties();
        records.setEnabled(enabled);
        IntentRecognitionResult result = new IntentServiceResponseMapper(
                copying, new IntentServiceHttpProperties(), records).toRecognitionResult(raw);

        assertThat(result.waitingClarification()).isTrue();
        assertThat(result.auditRawResponse()).isEmpty();
        verify(copying, never()).convertValue(same(raw), any(TypeReference.class));
    }

    @Test
    void auditCaptureFailureDoesNotChangeClarificationOrProtocolValidation() throws Exception {
        JsonNode raw = ambiguousResponse();
        ObjectMapper copying = spy(objectMapper);
        doThrow(new IllegalArgumentException("audit copy failed"))
                .when(copying).convertValue(same(raw), any(TypeReference.class));
        IntentRecordProperties records = new IntentRecordProperties();
        records.setEnabled(true);
        IntentServiceResponseMapper mapper = new IntentServiceResponseMapper(
                copying, new IntentServiceHttpProperties(), records);
        IntentRecognitionResult result = mapper.toRecognitionResult(raw);

        assertThat(result.waitingClarification()).isTrue();
        assertThat(result.auditRawResponse()).isEmpty();
        assertThat(result.clarificationPayload()).containsKey("candidateIntents");
        assertThat(mapper.toRecognitionResult(response("invalid", null)).status())
                .isEqualTo(IntentRecognitionResult.Status.FAILED_OR_DEGRADED);
    }

    private JsonNode ambiguousResponse() throws Exception {
        return objectMapper.readTree("""
                {"code":200,"status":"success","optionalDetail":null,"auditOnly":"audit-only-marker",
                 "data":{"sessionId":"intent-session","requestId":"intent-request","result":{
                   "routeAction":"CLARIFY","message":"choose a skill","items":[],
                   "clarification":{"type":"AMBIGUOUS_ROUTE","clarifyQuestion":"which skill?",
                     "candidateIntents":[
                       {"intentId":"b","accessName":"ex_skill-b","score":null},
                       {"intentId":"a","accessName":"ex_skill-a","detail":{"optional":null}}]}}}}
                """);
    }

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
    void appliesGeneralPrefixBeforeDomainExpertPrefix() throws Exception {
        IntentDecision decision = mapper("domain_agent_", "RE_")
                .toDecision(response("intent-expert", "domain_agent_RE_system-awareness"));

        assertThat(decision.candidateDomainAgentId()).isEqualTo("RE_system-awareness");
        assertThat(decision.slots()).containsEntry("accessName", "RE_system-awareness");
    }

    @Test
    void domainExpertPrefixWithoutRoleIsProtocolFailure() throws Exception {
        IntentRecognitionResult result = mapper("domain_agent_", "RE_")
                .toRecognitionResult(response("intent-expert", "domain_agent_RE_"));

        assertThat(result.status()).isEqualTo(IntentRecognitionResult.Status.FAILED_OR_DEGRADED);
        assertThat(result.decision().intentCode()).isEqualTo("finance.runtime.intent_error");
        assertThat(result.decision().raw()).containsEntry(
                "reason", "ROUTE_SINGLE domain expert accessName has no roleName");
    }

    @Test
    void sensitiveInformationMatchTakesPriorityOverDomainExpertPrefixValidation() throws Exception {
        IntentRecognitionResult result = mapper("domain_agent_", "RE_", "RE_")
                .toRecognitionResult(response("intent-sensitive", "domain_agent_RE_"));

        assertThat(result.status()).isEqualTo(IntentRecognitionResult.Status.FINAL);
        assertThat(result.decision().intentCode()).isEqualTo("intent-sensitive");
        assertThat(result.decision().candidateDomainAgentId()).isEqualTo("RE_");
        assertThat(result.decision().slots()).containsEntry("accessName", "RE_");
    }

    @Test
    void ambiguousDomainExpertPrefixWithoutRoleIsProtocolFailure() throws Exception {
        IntentRecognitionResult result = mapper("domain_agent_", "RE_").toRecognitionResult(
                objectMapper.readTree("""
                        {
                          "code": 200,
                          "status": "success",
                          "data": {
                            "result": {
                              "routeAction": "CLARIFY",
                              "clarification": {
                                "type": "AMBIGUOUS_ROUTE",
                                "candidateIntents": [
                                  {
                                    "intentId": "expert-intent",
                                    "intentName": "专家模式",
                                    "accessName": "domain_agent_RE_"
                                  }
                                ]
                              }
                            }
                          }
                        }
                        """));

        assertThat(result.status()).isEqualTo(IntentRecognitionResult.Status.FAILED_OR_DEGRADED);
        assertThat(result.decision().raw()).containsEntry(
                "reason", "CLARIFY domain expert accessName has no roleName");
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
    void ambiguousRouteCandidatesUseConfiguredAccessNameNormalization() throws Exception {
        IntentRecognitionResult result = mapper("domain_agent_").toRecognitionResult(objectMapper.readTree("""
                {
                  "code": 200,
                  "status": "success",
                  "data": {
                    "result": {
                      "routeAction": "CLARIFY",
                      "items": [],
                      "clarification": {
                        "type": "AMBIGUOUS_ROUTE",
                        "clarifyQuestion": "请选择技能",
                        "candidateIntents": [
                          {
                            "intentId": "deep-analysis",
                            "intentName": "深度分析",
                            "confidence": 0.91,
                            "accessName": "domain_agent_deep_analysis"
                          }
                        ]
                      }
                    }
                  }
                }
                """));

        assertThat(result.waitingClarification()).isTrue();
        assertThat(result.clarificationPayload().get("candidateIntents"))
                .isEqualTo(List.of(Map.of(
                        "intentId", "deep-analysis",
                        "intentName", "深度分析",
                        "confidence", 0.91,
                        "accessName", "domain_agent_deep_analysis",
                        "skillId", "deep_analysis")));
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
        return mapper(prefix, "");
    }

    private IntentServiceResponseMapper mapper(String prefix, String domainExpertPrefix) {
        return mapper(prefix, domainExpertPrefix, "");
    }

    private IntentServiceResponseMapper mapper(String prefix,
                                               String domainExpertPrefix,
                                               String sensitiveInformationAccessName) {
        IntentServiceHttpProperties properties = new IntentServiceHttpProperties();
        properties.setResponseAccessNamePrefix(prefix);
        properties.setDomainExpertAccessNamePrefix(domainExpertPrefix);
        properties.setSensitiveInformationAccessName(sensitiveInformationAccessName);
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
