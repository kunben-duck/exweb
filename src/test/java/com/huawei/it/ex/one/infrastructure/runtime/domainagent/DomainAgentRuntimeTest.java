package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class DomainAgentRuntimeTest {
    @Test
    void forwardsTrustedUserMessageIdToDomainAgentRequest() {
        AtomicReference<DomainAgentRequest> captured = new AtomicReference<>();
        DomainAgentClient client = new DomainAgentClient() {
            @Override
            public Flux<ChatEvent> query(DomainAgentRequest request) {
                captured.set(request);
                return Flux.empty();
            }

            @Override
            public Mono<Void> cancel(DomainAgentCancelRequest request) {
                return Mono.empty();
            }
        };
        DomainAgentRuntime runtime = new DomainAgentRuntime(client);
        AgentRuntimeRequest request = new AgentRuntimeRequest(
                "tenant1",
                "user1",
                "account1",
                1L,
                "session1",
                "run1",
                "session1",
                RuntimeSessionMode.RESUME,
                "hello",
                List.of(),
                List.of(),
                MemoryContext.empty(),
                null,
                RouteTarget.domainAgent("skill-tax", "intent-agent", 1.0, "matched"),
                Map.of("messageId", "forged-message"),
                Map.of(),
                RuntimeForwardHeaders.empty(),
                TraceContext.empty(),
                "msg-user-1");

        StepVerifier.create(runtime.query(request))
                .assertNext(event -> assertThat(event.payload())
                        .containsEntry("metadataType", "selected_domain_agent"))
                .verifyComplete();

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().messageId()).isEqualTo("msg-user-1");
        assertThat(captured.get().metadata()).containsEntry("messageId", "forged-message");
    }
}
