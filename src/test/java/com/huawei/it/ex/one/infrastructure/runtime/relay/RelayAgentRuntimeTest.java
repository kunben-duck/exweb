package com.huawei.it.ex.one.infrastructure.runtime.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.MessageDeltaEvent;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class RelayAgentRuntimeTest {
    @Test
    void pinnedDomainExpertPrependsSelectionEvent() {
        RelayAgentRuntime runtime = new RelayAgentRuntime(adapter());
        Map<String, Object> metadata = pinnedMetadata();
        AgentRuntimeRequest request = request(
                RouteTarget.domainExpertRuntime(
                        "front-selected", 1.0, "selected", "financial-analysis", "financial-analysis"),
                metadata);

        StepVerifier.create(runtime.query(request))
                .assertNext(event -> assertThat(event.payload())
                        .containsEntry("metadataType", "selected_domain_expert")
                        .containsEntry("roleName", "financial-analysis")
                        .containsEntry("intentId", "finance_analysis")
                        .containsEntry("intentName", "经营分析专家"))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .verifyComplete();
    }

    @Test
    void dynamicDomainExpertAndDelegateKeepOriginalEventStream() {
        RelayAgentRuntime runtime = new RelayAgentRuntime(adapter());
        Map<String, Object> dynamicExpert = RuntimeProfileMetadata.bindingMetadata(
                RuntimeProfile.DOMAIN_EXPERT, "delegate", "domain_expert", "financial-analysis");

        StepVerifier.create(runtime.query(request(
                        RouteTarget.domainExpertRuntime(
                                "intent-agent", 1.0, "selected", "financial-analysis", "finance_analysis"),
                        dynamicExpert)))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .verifyComplete();

        StepVerifier.create(runtime.query(request(
                        RouteTarget.agentRuntime("intent-fallback", 0.0, "fallback"),
                        RuntimeProfileMetadata.bindingMetadata(
                                RuntimeProfile.DELEGATE, "delegate", "domain_expert", null))))
                .expectNextMatches(event -> "message.delta".equals(event.type()))
                .verifyComplete();
    }

    private AgentRuntimeRequest request(RouteTarget route, Map<String, Object> bindingMetadata) {
        return new AgentRuntimeRequest(
                "tenant", "user", "account", 1L,
                "session", "run", "runtime-session", RuntimeSessionMode.RESUME,
                "question", List.of(), List.of(), MemoryContext.empty(), null, route,
                Map.of(), bindingMetadata, RuntimeForwardHeaders.empty(), TraceContext.empty(), "msg-user");
    }

    private Map<String, Object> pinnedMetadata() {
        Map<String, Object> metadata = new LinkedHashMap<>(RuntimeProfileMetadata.bindingMetadata(
                RuntimeProfile.DOMAIN_EXPERT, "delegate", "domain_expert", "financial-analysis"));
        metadata.put(RuntimeProfileMetadata.RELAY_EXPERT_PINNED_KEY, true);
        metadata.put("intentCode", "finance_analysis");
        metadata.put("intentName", "经营分析专家");
        metadata.put("routeSource", "front-selected");
        return Map.copyOf(metadata);
    }

    private RelayRuntimeProtocolAdapter adapter() {
        return new RelayRuntimeProtocolAdapter() {
            @Override
            public Flux<ChatEvent> query(AgentRuntimeRequest request) {
                return Flux.just(MessageDeltaEvent.of(request.runId(), request.sessionId(), "answer"));
            }

            @Override
            public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
                return Mono.empty();
            }
        };
    }
}
