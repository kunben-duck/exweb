/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime.relay;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntime;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteraction;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.it.ex.one.application.integration.agent.DomainExpertSelectionPayload;
import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeProfileMetadata;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * RelayAgent Runtime 防腐层实现。
 *
 * <p>该类是 application 层看到的唯一 Relay provider。它不直接拼接下游请求，
 * 统一委托给 Relay WebSocket 协议防腐层。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.agent-runtime.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RelayAgentRuntime implements AgentRuntime, AgentRuntimeInteraction {
    public static final String PROVIDER = "relay";

    private final RelayRuntimeProtocolAdapter protocolAdapter;

    public RelayAgentRuntime(RelayRuntimeProtocolAdapter protocolAdapter) {
        this.protocolAdapter = protocolAdapter;
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        if (selectedDomainExpert(request.bindingMetadata())) {
            String roleName = request.routeTarget() == null
                    ? null
                    : request.routeTarget().runtimeRoleName();
            if (roleName == null || roleName.isBlank()) {
                Object bindingRoleName = request.bindingMetadata().get(RuntimeProfileMetadata.ROLE_NAME_KEY);
                roleName = bindingRoleName == null ? null : String.valueOf(bindingRoleName);
            }
            String routeSource = request.routeTarget() == null
                    ? null
                    : request.routeTarget().routeSource();
            ChatEvent selected = RuntimeEvent.metadata(
                    request.runId(),
                    request.sessionId(),
                    DomainExpertSelectionPayload.create(
                            roleName, routeSource, request.bindingMetadata()));
            return Flux.concat(Flux.just(selected), protocolAdapter.query(request));
        }
        return protocolAdapter.query(request);
    }

    @Override
    public boolean supportsWaitingUserResponse(String runtimeProvider) {
        return PROVIDER.equalsIgnoreCase(runtimeProvider) && protocolAdapter.supportsUserResponseContinuation();
    }

    @Override
    public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
        if (selectedDomainExpert(request.runtimeMetadata())) {
            Object roleName = request.runtimeMetadata().get(RuntimeProfileMetadata.ROLE_NAME_KEY);
            Object routeSource = request.runtimeMetadata().get("routeSource");
            ChatEvent selected = RuntimeEvent.metadata(
                    request.runId(),
                    request.sessionId(),
                    DomainExpertSelectionPayload.create(
                            roleName == null ? null : String.valueOf(roleName),
                            routeSource == null ? "interaction-continuation" : String.valueOf(routeSource),
                            request.runtimeMetadata()));
            return Flux.concat(Flux.just(selected), protocolAdapter.continueWithUserResponse(request));
        }
        return protocolAdapter.continueWithUserResponse(request);
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return protocolAdapter.cancel(request);
    }

    private boolean selectedDomainExpert(java.util.Map<String, Object> metadata) {
        return RuntimeProfileMetadata.isPinnedDomainExpert(metadata)
                || (IntentExpertContext.scopedDomainExpert(metadata)
                && RuntimeProfile.DOMAIN_EXPERT.name().equals(
                        String.valueOf(metadata.get(RuntimeProfileMetadata.PROFILE_KEY))));
    }
}
