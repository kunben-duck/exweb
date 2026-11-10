/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.IntentServiceIdentity;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceHttpProperties;
import com.huawei.it.ex.one.infrastructure.intent.IntentServiceResponseMapper;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

class IntentServiceIdentityPropagationTest {
    private final ObjectMapper json = new ObjectMapper();
    private final RouteResolutionCoordinator routes = new RouteResolutionCoordinator(null, null, null);

    @Test
    void singleResultPreservesRawIdThroughBindingPersistenceAndRejectContext() throws Exception {
        IntentDecision decision = mapper().toDecision(json.readTree("""
                {"code":200,"data":{"status":"success","result":{"routeAction":"ROUTE_SINGLE","items":[
                  {"intentId":"EX_original-id","intentName":"Knowledge","accessName":"EX_skill-a"},
                  {"intentId":"wrong-id","intentName":"Other","accessName":"EX_skill-b"}]}}}
                """));
        Map<String, Object> metadata = routes.domainAgentBindingMetadata(
                RouteTarget.domainAgent("skill-a", "intent-agent"), decision);
        Map<String, Object> restored = json.readValue(json.writeValueAsString(metadata), new TypeReference<>() { });
        assertThat(IntentServiceIdentity.bindingId(restored, "skill-a", "Knowledge")).isEqualTo("EX_original-id");
        var reason = DomainAgentRejectReason.from("Knowledge",
                IntentServiceIdentity.bindingId(restored, "skill-a", "Knowledge"), null);
        assertThat(DomainAgentRejectReason.fromRerouteState(Map.of("lastIntentRejectReason", reason.toMap()))
                .lastIntentId()).isEqualTo("EX_original-id");
    }

    @Test
    void ambiguousSelectionUsesTheSavedSelectedCandidateId() throws Exception {
        var result = mapper().toRecognitionResult(json.readTree("""
                {"code":200,"data":{"status":"success","result":{"routeAction":"CLARIFY",
                  "clarification":{"type":"AMBIGUOUS_ROUTE","clarifyQuestion":"Which skill?",
                    "candidateIntents":[
                      {"intentId":"first-id","intentName":"First","accessName":"EX_skill-a"},
                      {"intentId":"EX_second-id","intentName":"Second","accessName":"EX_skill-b"}]}}}}
                """));
        Map<String, Object> saved = json.readValue(json.writeValueAsString(result.clarificationPayload()),
                new TypeReference<>() { });
        var resolver = new AmbiguousRouteSelectionResolver();
        var selected = resolver.candidates(saved).get(1);
        var signal = resolver.routeSignal(selected, "user-confirmed");
        Map<String, Object> binding = routes.domainAgentBindingMetadata(signal.route(), signal.intentDecision());
        assertThat(IntentServiceIdentity.id(signal.intentDecision())).isEqualTo("EX_second-id");
        assertThat(IntentServiceIdentity.bindingId(binding, "skill-b", "Second")).isEqualTo("EX_second-id");
    }

    @Test
    void explicitClientSelectionClearsPreviouslyRecognizedIdentityWithoutChangingDisplayFields() throws Exception {
        IntentDecision decision = mapper().toDecision(json.readTree("""
                {"code":200,"data":{"status":"success","result":{"routeAction":"ROUTE_SINGLE","items":[
                  {"intentId":"original-id","intentName":"Knowledge","accessName":"EX_skill-a"}]}}}
                """));
        var route = RouteTarget.domainAgent("skill-a", "front-selected");
        Map<String, Object> merged = new LinkedHashMap<>(routes.domainAgentBindingMetadata(route, decision));
        merged.putAll(routes.domainAgentBindingMetadata(route, null,
                SelectedIntentContext.attach(Map.of(), "client-id", "Client name")));
        assertThat(merged).containsEntry("intentCode", "client-id").containsEntry("intentName", "Client name");
        assertThat(IntentServiceIdentity.bindingId(merged, "skill-a", "Client name")).isNull();
        assertThat(IntentServiceIdentity.bindingId(merged, "skill-a", "Knowledge")).isNull();
    }

    private IntentServiceResponseMapper mapper() {
        var properties = new IntentServiceHttpProperties();
        properties.setResponseAccessNamePrefix("EX_");
        return new IntentServiceResponseMapper(json, properties);
    }
}
