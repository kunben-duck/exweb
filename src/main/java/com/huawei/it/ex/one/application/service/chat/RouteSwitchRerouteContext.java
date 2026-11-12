/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.IntentExpertScope;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** 确认前尚无候选的切换上下文，只从服务端持久化 Interaction 恢复。 */
record RouteSwitchRerouteContext(String originalQuery, String routeQuery,
                                 DomainAgentRerouteState state, IntentExpertScope expertScope,
                                 AgentDataPersistenceState persistenceState) {
    static final String STAGE_KEY = "confirmationStage";
    static final String BEFORE_INTENT = "BEFORE_INTENT";
    static final String PRIVATE_KEY = "_routeSwitchRerouteContext";

    static boolean authorized(ChatCommand command) {
        DomainAgentRerouteState state = new DomainAgentRerouteStateMapper().from(command);
        return state != null && state.confirmationInteractionId() != null;
    }

    static AgentDataPersistenceState continuationPersistenceState(ChatCommand command) {
        if (!authorized(command)) {
            return AgentDataPersistenceState.full();
        }
        return AgentDataPersistenceState.inheritFromRunMetadata(DomainAgentQuestionnaireContext.map(
                command.metadata().get(RouteResolutionCoordinator.DOMAIN_AGENT_REROUTE_CONTEXT_METADATA)), null);
    }

    static void validateContinuationScope(ChatInteractionRequest interaction, IntentExpertScope currentScope) {
        Map<String, Object> state = DomainAgentQuestionnaireContext.map(interaction.requestPayload().get(
                RouteResolutionCoordinator.DOMAIN_AGENT_REROUTE_CONTEXT_METADATA));
        if (state.get("confirmationInteractionId") != null
                && !IntentExpertContext.matches(state, currentScope)) {
            throw ChatInteractionUnavailableException.alreadyHandled(interaction.id());
        }
    }

    static Flux<ChatEvent> afterResponse(ChatCommand command, ChatEvent response,
                                        Supplier<Flux<ChatEvent>> continuation) {
        if (!authorized(command)) {
            return Flux.concat(Flux.just(response), continuation.get());
        }
        return Flux.defer(() -> {
            Sinks.One<Void> committed = Sinks.one();
            return Flux.concat(Flux.just(new PersistenceAcknowledgedEvent(response, committed)),
                    committed.asMono().thenMany(Flux.defer(continuation)));
        });
    }

    static boolean beforeIntent(ChatInteractionRequest interaction) {
        Object stage = interaction.requestPayload().get(STAGE_KEY);
        if (stage == null) {
            return false;
        }
        if (!BEFORE_INTENT.equals(stage)) {
            throw new IllegalStateException("不支持的路由切换确认阶段");
        }
        return true;
    }

    static RouteSwitchRerouteContext require(ChatInteractionRequest interaction) {
        Map<String, Object> snapshot = DomainAgentQuestionnaireContext.map(
                interaction.requestPayload().get(PRIVATE_KEY));
        Map<String, Object> rawState = DomainAgentQuestionnaireContext.map(
                snapshot.get(RouteResolutionCoordinator.DOMAIN_AGENT_REROUTE_CONTEXT_METADATA));
        DomainAgentRerouteState state = new DomainAgentRerouteStateMapper().fromState(rawState);
        if (state == null || !Objects.equals(state.currentBindingId(), interaction.runtimeBindingId())
                || !Objects.equals(state.currentTargetId(),
                interaction.requestPayload().get("currentTargetId"))) {
            throw new IllegalStateException("路由切换缺少可信拒答上下文");
        }
        String query = DomainAgentQuestionnaireContext.text(snapshot.get("originalQuery"));
        String routeQuery = DomainAgentQuestionnaireContext.text(snapshot.get("routeMemoryQuery"));
        return new RouteSwitchRerouteContext(query == null ? "" : query,
                routeQuery == null ? query : routeQuery, state,
                IntentExpertContext.fromMetadata(snapshot).orElse(null),
                AgentDataPersistenceState.inheritFromRunMetadata(snapshot, null));
    }
}
