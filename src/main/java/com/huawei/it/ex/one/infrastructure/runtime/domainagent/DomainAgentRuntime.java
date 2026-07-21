package com.huawei.it.ex.one.infrastructure.runtime.domainagent;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentClient;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainAgentSelectionPayload;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * DomainAgent Runtime provider。
 *
 * <p>DomainAgent 与 Relay 一样通过 AgentRuntime 防腐层执行，主编排只按
 * RuntimeBinding.provider 选择 provider。该实现只负责协议适配、标准事件输出和 best-effort cancel；
 * 拒答后的重路由策略仍由 ChatService 应用层控制。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.domain-agent", name = "enabled", havingValue = "true")
public class DomainAgentRuntime implements AgentRuntime {
    public static final String PROVIDER = "domain-agent";

    private final DomainAgentClient client;

    public DomainAgentRuntime(DomainAgentClient client) {
        this.client = client;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        String domainAgentId = domainAgentId(request);
        DomainAgentRequest domainRequest = new DomainAgentRequest(
                user(request),
                request.sessionId(),
                request.runId(),
                domainAgentId,
                runtimeSessionId(request),
                request.message(),
                request.documents(),
                request.agentModeParameters().mergeRequestMetadata(request.metadata()),
                request.forwardHeaders()
        );
        return Flux.concat(Flux.just(selectedDomainAgentEvent(domainRequest, request)), client.query(domainRequest));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return client.cancel(new DomainAgentCancelRequest(
                user(request),
                request.sessionId(),
                request.runId(),
                request.runtimeTargetId(),
                request.reason(),
                request.metadata(),
                request.forwardHeaders()
        ));
    }

    private String domainAgentId(AgentRuntimeRequest request) {
        String value = request.routeTarget() == null ? null : request.routeTarget().selectedAgentCode();
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("DomainAgent Runtime 缺少目标 DomainAgentId");
        }
        return value.trim();
    }

    private String runtimeSessionId(AgentRuntimeRequest request) {
        return request.runtimeSessionId() == null || request.runtimeSessionId().isBlank()
                ? request.sessionId()
                : request.runtimeSessionId();
    }

    private UserContext user(AgentRuntimeRequest request) {
        return new UserContext(request.tenantId(), request.userId(), request.userAccount(),
                request.userAccount(), null, request.userAccount(), "UNKNOWN", request.userId(),
                null, request.userAccount(), request.userAccount(), request.globalUserId());
    }

    private UserContext user(AgentRuntimeCancelRequest request) {
        return new UserContext(request.tenantId(), request.userId(), request.userId());
    }

    private ChatEvent selectedDomainAgentEvent(DomainAgentRequest request, AgentRuntimeRequest runtimeRequest) {
        String routeSource = runtimeRequest.routeTarget() == null || runtimeRequest.routeTarget().routeSource() == null
                ? "runtime-binding"
                : runtimeRequest.routeTarget().routeSource();
        return RuntimeEvent.metadata(request.runId(), request.sessionId(),
                DomainAgentSelectionPayload.create(request.domainAgentId(), routeSource,
                        runtimeSessionId(runtimeRequest), runtimeRequest.intentDecision(),
                        runtimeRequest.bindingMetadata()));
    }
}
