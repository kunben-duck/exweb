package com.huawei.it.ex.one.intent.application.coordinator;

import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.application.client.IntentAgentRuntime;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.IntentRecognitionResult;
import com.huawei.it.ex.one.intent.application.model.IntentRouteContext;
import com.huawei.it.ex.one.intent.application.model.RouteSignalFrame;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import java.util.LinkedHashMap;
import java.util.Map;
import reactor.core.publisher.Flux;

/** Creates the existing persisted intent-result event and its following route frame. */
public final class IntentResultFrameFactory {

    public Flux<RouteSignalFrame> frames(IntentRouteContext request, RouteSignalResult routeResult,
                                         IntentDecision intent, long latencyMs, String routeAction) {
        RuntimeEvent event = event(request, routeResult, intent, latencyMs, routeAction);
        return event == null
                ? Flux.just(RouteSignalFrame.result(routeResult))
                : Flux.just(RouteSignalFrame.event(event), RouteSignalFrame.result(routeResult));
    }

    public String routeAction(IntentRecognitionResult result, IntentDecision intent, RouteTarget route) {
        if (result != null && result.status() == IntentRecognitionResult.Status.FAILED_OR_DEGRADED) {
            return "DEGRADED";
        }
        Object fromSlots = intent == null || intent.slots() == null ? null : intent.slots().get("routeAction");
        if (fromSlots != null && !String.valueOf(fromSlots).isBlank()) {
            return String.valueOf(fromSlots);
        }
        if (route != null && route.type() == RouteType.DOMAIN_AGENT) {
            return "ROUTE_SINGLE";
        }
        return route != null && route.type() == RouteType.AGENT_RUNTIME ? "NO_MATCH" : "UNKNOWN";
    }

    private RuntimeEvent event(IntentRouteContext request, RouteSignalResult routeResult,
                               IntentDecision intent, long latencyMs, String routeAction) {
        if (!canCreateEvent(request)) {
            return null;
        }
        RouteTarget route = routeResult == null ? null : routeResult.route();
        Map<String, Object> payload = new LinkedHashMap<>();
        putBaseFields(payload, request, latencyMs, routeAction);
        putIntentFields(payload, intent);
        putRouteFields(payload, route);
        putFailureFields(payload, routeResult);
        return RuntimeEvent.progress(request.runId(), request.session().id(), Map.copyOf(payload));
    }

    private boolean canCreateEvent(IntentRouteContext request) {
        return request != null && request.runId() != null && !request.runId().isBlank()
                && request.session() != null && request.session().id() != null
                && !request.session().id().isBlank();
    }

    private void putBaseFields(Map<String, Object> payload, IntentRouteContext request,
                               long latencyMs, String routeAction) {
        payload.put("source", IntentAgentRuntime.PROVIDER);
        payload.put("sourceType", "intent-result");
        payload.put("stage", "intent_result");
        payload.put("message", "已完成意图识别");
        payload.put("routeAction", blankToDefault(routeAction, "UNKNOWN"));
        payload.put("routeTrigger", request.routeTrigger() == null ? "" : request.routeTrigger());
        payload.put("latencyMs", latencyMs);
    }

    private void putIntentFields(Map<String, Object> payload, IntentDecision intent) {
        if (intent == null) {
            return;
        }
        payload.put("intentCode", blankToDefault(intent.intentCode(), ""));
        payload.put("intentId", blankToDefault(firstText(intent.slots().get("intentId"), intent.intentCode()), ""));
        payload.put("intentName", blankToDefault(intent.intentName(), ""));
        payload.put("confidence", intent.confidence());
        if (intent.candidateDomainAgentId() != null && !intent.candidateDomainAgentId().isBlank()) {
            payload.put("skillId", intent.candidateDomainAgentId());
        }
    }

    private void putRouteFields(Map<String, Object> payload, RouteTarget route) {
        if (route == null || route.type() == null) {
            return;
        }
        payload.put("routeType", route.type().name());
        payload.put("routeSource", blankToDefault(route.routeSource(), IntentAgentRuntime.PROVIDER));
        if (route.type() == RouteType.DOMAIN_AGENT) {
            payload.put("targetProvider", "domain-agent");
            payload.put("targetId", blankToDefault(route.selectedAgentCode(), ""));
        } else if (route.type() == RouteType.AGENT_RUNTIME) {
            payload.put("targetProvider", "relay");
        } else {
            payload.put("targetProvider", "system");
        }
    }

    private void putFailureFields(Map<String, Object> payload, RouteSignalResult routeResult) {
        if (routeResult == null || !routeResult.intentFailure()) {
            return;
        }
        payload.put("failureStrategy", routeResult.intentFailureStrategy().name());
        if (routeResult.failRunOnIntentFailure()) {
            payload.put("targetProvider", "none");
            payload.put("suggestedAction", "SELECT_DOMAIN_AGENT");
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
}
