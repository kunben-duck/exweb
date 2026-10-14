/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.IntentExpertContext;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.service.routing.RouteSignalProgress;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatPayloadMaps;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentControlEventMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Creates the existing events and private command metadata for DomainAgent refusal rerouting. */
final class DomainAgentRefusalEventFactory {
    RuntimeEvent replacementEvent(DomainAgentRerouteContext reroute, RouteTarget route, String action) {
        if (!reroute.confirmed()) {
            return rerouteMetadata(reroute.context(), reroute.refusal(), route, action);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-applied");
        payload.put("metadataType", "route_switch_applied");
        payload.put("interactionId", reroute.confirmationInteractionId());
        payload.put("targetProvider", route.type() == RouteType.DOMAIN_AGENT ? "domain-agent" : "relay");
        payload.put("targetId", route.type() == RouteType.DOMAIN_AGENT ? route.selectedAgentCode() : "relay");
        payload.put("routeSource", route.routeSource());
        return RuntimeEvent.metadata(reroute.context().runId(), reroute.context().session().id(), Map.copyOf(payload));
    }

    RuntimeEvent beforeIntentConfirmation(DomainAgentRerouteContext reroute, String routeSource) {
        DomainAgentRunContext context = reroute.context();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-confirmation-request");
        payload.put("interactionType", ChatInteractionType.ROUTE_SWITCH_CONFIRMATION.name());
        payload.put(RouteSwitchRerouteContext.STAGE_KEY, RouteSwitchRerouteContext.BEFORE_INTENT);
        payload.put("currentProvider", RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        payload.put("currentTargetId", reroute.currentDomainAgentId());
        putIfNotNull(payload, "currentRouteSource", routeSource);
        payload.put("message", "当前技能无法处理该问题，是否重新识别并选择合适技能继续回答？");
        putIfNotNull(payload, "refusalCode", reroute.refusal().code());
        putIfNotNull(payload, "refusalReasonCode", reroute.refusal().reasonCode());
        putIfNotNull(payload, "refusalRecoverable", reroute.refusal().recoverable());
        putIfNotNull(payload, "refusalReason", reroute.refusal().message());
        payload.put("originalQuery", context.command().message() == null ? "" : context.command().message());
        Map<String, Object> snapshot = new LinkedHashMap<>(clarificationPayload(reroute, Map.of(), routeSource));
        snapshot.put("originalQuery", payload.get("originalQuery"));
        putIfNotNull(snapshot, "routeMemoryQuery", context.routeMemoryQuery());
        snapshot.putAll(context.persistenceState().runMetadataOverlay());
        snapshot = IntentExpertContext.withScope(
                snapshot, context.command().intentExpertScope());
        // 私有状态仅随 WAIT 事务保存，不将其混入可发布、可分享的卡片。
        context.pendingInteractionPayloadRef().set(Map.of(RouteSwitchRerouteContext.PRIVATE_KEY, snapshot));
        return RuntimeEvent.card(context.runId(), context.session().id(), Map.copyOf(payload));
    }

    ChatEvent enrichControlEvent(ChatEvent event, String domainAgentId) {
        if (event == null || event.payload() == null
                || DomainAgentControlEventMapper.fromNormalizedPayload(event.payload()).isEmpty()) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        putIfNotNull(payload, "domainAgentId", domainAgentId);
        putIfNotNull(payload, "targetId", domainAgentId);
        payload.put("provider", RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        return new RuntimeEvent(
                event.runId(),
                event.sessionId(),
                event.sequence(),
                event.createdAt(),
                event.type(),
                ChatPayloadMaps.immutableCopy(payload));
    }

    ChatCommand commandWithDomainRejectContext(ChatCommand command,
                                               DomainAgentRejectReason rejectReason) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                SelectedIntentContext.removeReserved(command.metadata()));
        metadata.put("routeTrigger", "domain_reject");
        metadata.put(DomainAgentRejectReason.METADATA_KEY, rejectReason.toMap());
        return new ChatCommand(
                command.commandId(),
                command.tenantId(),
                command.userId(),
                command.sessionId(),
                command.conversationId(),
                command.channel(),
                command.message(),
                command.attachments(),
                metadata,
                command.targetType(),
                command.targetId(),
                command.runMode(),
                command.parentMessageId(),
                command.editedMessageId(),
                command.regeneratedMessageId(),
                "domain_reject",
                command.interactionId(),
                command.approved(),
                command.scope(),
                command.questionnaireAnswers(),
                command.appId(),
                command.appName(),
                command.agentMode(),
                command.interactionAction(),
                command.language(),
                command.intentAccessName(),
                command.intentExpertScope());
    }

    RuntimeEvent routeSwitchConfirmationRequest(DomainAgentRerouteContext reroute,
                                                RouteSignalResult nextSignal,
                                                String currentRouteSource) {
        DomainAgentRunContext context = reroute.context();
        DomainAgentRefusal refusal = reroute.refusal();
        RouteTarget candidate = nextSignal.route();
        String candidateProvider = candidate.type() == RouteType.DOMAIN_AGENT
                ? RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER
                : RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER;
        String candidateTargetId = candidate.type() == RouteType.DOMAIN_AGENT
                ? candidate.selectedAgentCode()
                : RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-confirmation-request");
        payload.put("interactionType", ChatInteractionType.ROUTE_SWITCH_CONFIRMATION.name());
        payload.put("currentProvider", RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        payload.put("currentTargetId", context.route().selectedAgentCode());
        payload.put("currentRouteSource", currentRouteSource);
        payload.put("candidateProvider", candidateProvider);
        payload.put("candidateTargetId", candidateTargetId);
        payload.put("message", "当前领域 Agent 无法处理该问题，是否切换到新的处理能力继续回答？");
        IntentDecision intent = nextSignal.intentDecision();
        putIfNotNull(payload, "candidateIntentCode", intent == null ? null : intent.intentCode());
        putIfNotNull(payload, "candidateIntentName", intent == null ? null : intent.intentName());
        putIfNotNull(payload, "candidateAccessName",
                intent == null ? null : intent.candidateDomainAgentId());
        putIfNotNull(payload, "candidateRuntimeRoleName", candidate.runtimeRoleName());
        putIfNotNull(payload, "routeAction", routeAction(intent));
        putIfNotNull(payload, "refusalCode", refusal.code());
        putIfNotNull(payload, "refusalReasonCode", refusal.reasonCode());
        putIfNotNull(payload, "refusalRecoverable", refusal.recoverable());
        putIfNotNull(payload, "refusalReason", refusal.message());
        payload.put("originalQuery", context.command().message() == null ? "" : context.command().message());
        putIfNotNull(payload, "routeMemoryQuery", reroute.intentQuery());
        putIfNotNull(payload, "candidateRouteSource", candidate.routeSource());
        return RuntimeEvent.card(
                context.runId(),
                context.session().id(),
                ChatPayloadMaps.immutableCopy(payload));
    }

    Map<String, Object> clarificationPayload(DomainAgentRerouteContext reroute,
                                             Map<String, Object> requestPayload,
                                             String currentRouteSource) {
        Map<String, Object> payload = new LinkedHashMap<>(mapOrEmpty(requestPayload));
        DomainAgentRunContext context = reroute.context();
        RuntimeBinding binding = context.bindingRef().get();
        Map<String, Object> rerouteState = new LinkedHashMap<>();
        rerouteState.put("currentProvider", RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        rerouteState.put("currentTargetId", reroute.currentDomainAgentId());
        putIfNotNull(rerouteState, "currentBindingId", binding == null ? null : binding.id());
        putIfNotNull(rerouteState, "currentRouteSource", currentRouteSource);
        putIfNotNull(rerouteState, "refusalCode", reroute.refusal().code());
        putIfNotNull(rerouteState, "refusalReasonCode", reroute.refusal().reasonCode());
        putIfNotNull(rerouteState, "refusalRecoverable", reroute.refusal().recoverable());
        putIfNotNull(rerouteState, "refusalReason", reroute.refusal().message());
        putIfNotNull(rerouteState, "refusalAgentId", reroute.refusal().agentId());
        rerouteState.put(DomainAgentRejectReason.METADATA_KEY, reroute.lastIntentRejectReason().toMap());
        rerouteState.put("rerouteCount", context.rerouteCount());
        putIfNotNull(rerouteState, "confirmationInteractionId", reroute.confirmationInteractionId());
        if (reroute.confirmed()) {
            rerouteState.putAll(context.persistenceState().runMetadataOverlay());
        }
        rerouteState.put("rejectedDomainAgentIds", List.copyOf(reroute.rejectedDomainAgentIds()));
        putIfNotNull(rerouteState, "originalQuery",
                firstText(context.routeMemoryQuery(), context.command().message()));
        payload.put(RouteResolutionCoordinator.DOMAIN_AGENT_REROUTE_CONTEXT_METADATA,
                reroute.confirmed()
                        ? IntentExpertContext.withScope(
                                rerouteState, context.command().intentExpertScope())
                        : Map.copyOf(rerouteState));
        return ChatPayloadMaps.immutableCopy(payload);
    }

    RuntimeEvent intentClarificationRequest(String runId,
                                            String sessionId,
                                            Map<String, Object> requestPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(mapOrEmpty(requestPayload));
        payload.put("source", "intent-agent");
        payload.put("sourceType", "intent-clarification-request");
        payload.put("interactionType", ChatInteractionType.INTENT_CLARIFICATION.name());
        return RuntimeEvent.card(runId, sessionId, Map.copyOf(payload));
    }

    RuntimeEvent rerouteMetadata(DomainAgentRunContext context,
                                 DomainAgentRefusal refusal,
                                 RouteTarget nextRoute,
                                 String action) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "domain-agent-reroute");
        payload.put("metadataType", "domain_agent_reroute");
        payload.put("action", action);
        putIfNotNull(payload, "currentDomainAgentId", context.route().selectedAgentCode());
        putIfNotNull(payload, "refusalCode", refusal.code());
        putIfNotNull(payload, "refusalReasonCode", refusal.reasonCode());
        putIfNotNull(payload, "refusalRecoverable", refusal.recoverable());
        putIfNotNull(payload, "refusalReason", refusal.message());
        if (nextRoute != null) {
            putIfNotNull(payload, "candidateDomainAgentId", nextRoute.selectedAgentCode());
            putIfNotNull(payload, "candidateRouteSource", nextRoute.routeSource());
        }
        return RuntimeEvent.metadata(context.runId(), context.session().id(), payload);
    }

    RuntimeEvent routeProgress(String runId, String sessionId, RouteSignalProgress progress) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-progress");
        payload.put("stage", progress == null
                ? "route_progress"
                : blankToDefault(progress.stage(), "route_progress"));
        payload.put("message", progress == null
                ? "正在选择合适能力"
                : blankToDefault(progress.message(), "正在选择合适能力"));
        if (progress != null && progress.attributes() != null) {
            progress.attributes().forEach((key, value) -> {
                if (key != null && value != null && !payload.containsKey(key)) {
                    payload.put(key, value);
                }
            });
        }
        return RuntimeEvent.progress(runId, sessionId, Map.copyOf(payload));
    }

    private String routeAction(IntentDecision intent) {
        return firstText(
                intent == null || intent.slots() == null ? null : intent.slots().get("routeAction"),
                intent == null || intent.raw() == null ? null : intent.raw().get("routeAction"));
    }

    private void putIfNotNull(Map<String, Object> target, String key, Object value) {
        if (target != null && key != null && value != null) {
            target.put(key, value);
        }
    }

    private String firstText(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private Map<String, Object> mapOrEmpty(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                copy.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return Map.copyOf(copy);
    }
}
