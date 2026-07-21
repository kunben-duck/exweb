package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.runtime.application.model.RuntimeProviders;

import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.ChatInteractionType;
import com.huawei.it.ex.one.common.event.ChatPayloadMaps;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentControlEvent;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentRefusal;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.common.metadata.SelectedIntentContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Maps DomainAgent refusal control state to the existing ChatEvent protocol. */
@Component
public class DomainAgentRefusalEventFactory {
    public static final String REROUTE_CONTEXT_METADATA = "domainAgentRerouteContext";

    public ChatEvent enrichControlEvent(ChatEvent event, String domainAgentId) {
        if (event == null || event.payload() == null
                || DomainAgentControlEvent.fromNormalizedPayload(event.payload()).isEmpty()) {
            return event;
        }
        Map<String, Object> payload = new LinkedHashMap<>(event.payload());
        putIfNotNull(payload, "domainAgentId", domainAgentId);
        putIfNotNull(payload, "targetId", domainAgentId);
        payload.put("provider", RuntimeProviders.DOMAIN_AGENT);
        return new RuntimeEvent(event.runId(), event.sessionId(), event.sequence(), event.createdAt(),
                event.type(), ChatPayloadMaps.immutableCopy(payload));
    }

    public RuntimeEvent routeSwitchConfirmationRequest(SwitchConfirmation context) {
        RouteTarget candidate = context.signal().route();
        String candidateProvider = candidate.type() == RouteType.DOMAIN_AGENT
                ? RuntimeProviders.DOMAIN_AGENT
                : RuntimeProviders.RELAY;
        String candidateTargetId = candidate.type() == RouteType.DOMAIN_AGENT
                ? candidate.selectedAgentCode()
                : RuntimeProviders.RELAY;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "route-switch-confirmation-request");
        payload.put("interactionType", ChatInteractionType.ROUTE_SWITCH_CONFIRMATION.name());
        payload.put("currentProvider", RuntimeProviders.DOMAIN_AGENT);
        payload.put("currentTargetId", context.currentDomainAgentId());
        payload.put("currentRouteSource", routeSource(context.currentBinding()));
        payload.put("candidateProvider", candidateProvider);
        payload.put("candidateTargetId", candidateTargetId);
        payload.put("message", "当前领域 Agent 无法处理该问题，是否切换到新的处理能力继续回答？");
        IntentDecision intent = context.signal().intentDecision();
        putIfNotNull(payload, "candidateIntentCode", intent == null ? null : intent.intentCode());
        putIfNotNull(payload, "candidateIntentName", intent == null ? null : intent.intentName());
        putIfNotNull(payload, "routeAction", routeAction(intent));
        putIfNotNull(payload, "refusalCode", context.refusal().code());
        putIfNotNull(payload, "refusalReasonCode", context.refusal().reasonCode());
        putIfNotNull(payload, "refusalRecoverable", context.refusal().recoverable());
        putIfNotNull(payload, "refusalReason", context.refusal().message());
        payload.put("originalQuery", context.originalQuery() == null ? "" : context.originalQuery());
        putIfNotNull(payload, "routeMemoryQuery", context.routeMemoryQuery());
        putIfNotNull(payload, "candidateRouteSource", candidate.routeSource());
        return RuntimeEvent.card(context.runId(), context.sessionId(), ChatPayloadMaps.immutableCopy(payload));
    }

    public Map<String, Object> clarificationPayload(ReroutePayloadContext context,
                                                    Map<String, Object> requestPayload) {
        Map<String, Object> payload = new LinkedHashMap<>(mapOrEmpty(requestPayload));
        Map<String, Object> rerouteState = new LinkedHashMap<>();
        rerouteState.put("currentProvider", RuntimeProviders.DOMAIN_AGENT);
        rerouteState.put("currentTargetId", context.currentDomainAgentId());
        putIfNotNull(rerouteState, "currentBindingId",
                context.currentBinding() == null ? null : context.currentBinding().id());
        putIfNotNull(rerouteState, "currentRouteSource", routeSource(context.currentBinding()));
        putIfNotNull(rerouteState, "refusalCode", context.refusal().code());
        putIfNotNull(rerouteState, "refusalReasonCode", context.refusal().reasonCode());
        putIfNotNull(rerouteState, "refusalRecoverable", context.refusal().recoverable());
        putIfNotNull(rerouteState, "refusalReason", context.refusal().message());
        putIfNotNull(rerouteState, "refusalAgentId", context.refusal().agentId());
        rerouteState.put("rerouteCount", context.rerouteCount());
        rerouteState.put("rejectedDomainAgentIds", List.copyOf(context.rejectedDomainAgentIds()));
        putIfNotNull(rerouteState, "originalQuery",
                firstText(context.routeMemoryQuery(), context.originalQuery()));
        payload.put(REROUTE_CONTEXT_METADATA, Map.copyOf(rerouteState));
        return ChatPayloadMaps.immutableCopy(payload);
    }

    public RuntimeEvent rerouteMetadata(RerouteMetadata context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", "chatservice");
        payload.put("sourceType", "domain-agent-reroute");
        payload.put("metadataType", "domain_agent_reroute");
        payload.put("action", context.action());
        putIfNotNull(payload, "currentDomainAgentId", context.currentDomainAgentId());
        putIfNotNull(payload, "refusalCode", context.refusal().code());
        putIfNotNull(payload, "refusalReasonCode", context.refusal().reasonCode());
        putIfNotNull(payload, "refusalRecoverable", context.refusal().recoverable());
        putIfNotNull(payload, "refusalReason", context.refusal().message());
        if (context.nextRoute() != null) {
            putIfNotNull(payload, "candidateDomainAgentId", context.nextRoute().selectedAgentCode());
            putIfNotNull(payload, "candidateRouteSource", context.nextRoute().routeSource());
        }
        return RuntimeEvent.metadata(context.runId(), context.sessionId(), payload);
    }

    public ChatCommand commandWithDomainRejectContext(ChatCommand command, String domainAgentId,
                                                      DomainAgentRefusal refusal) {
        Map<String, Object> metadata = new LinkedHashMap<>(
                SelectedIntentContext.removeReserved(command.metadata()));
        metadata.put("routeTrigger", "domain_reject");
        metadata.put("lastIntentRejectReason", Map.of(
                "lastDomainAgentId", domainAgentId,
                "domainRejectCode", refusal.code(),
                "domainRejectMessage", refusal.message() == null ? "" : refusal.message()
        ));
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(), metadata,
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), "domain_reject",
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName());
    }

    private String routeAction(IntentDecision intent) {
        return firstText(intent == null || intent.slots() == null ? null : intent.slots().get("routeAction"),
                intent == null || intent.raw() == null ? null : intent.raw().get("routeAction"));
    }

    private String routeSource(RuntimeBinding binding) {
        if (binding == null || binding.metadata() == null) {
            return null;
        }
        Object value = binding.metadata().get("routeSource");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private Map<String, Object> mapOrEmpty(Object value) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    copy.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return Map.copyOf(copy);
        }
        return Map.of();
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

    public record SwitchConfirmation(
            String runId,
            String sessionId,
            String currentDomainAgentId,
            RuntimeBinding currentBinding,
            DomainAgentRefusal refusal,
            RouteSignalResult signal,
            String originalQuery,
            String routeMemoryQuery
    ) {
    }

    public record ReroutePayloadContext(
            String currentDomainAgentId,
            RuntimeBinding currentBinding,
            DomainAgentRefusal refusal,
            int rerouteCount,
            Set<String> rejectedDomainAgentIds,
            String routeMemoryQuery,
            String originalQuery
    ) {
    }

    public record RerouteMetadata(
            String runId,
            String sessionId,
            String currentDomainAgentId,
            DomainAgentRefusal refusal,
            RouteTarget nextRoute,
            String action
    ) {
    }
}
