package com.huawei.it.ex.one.intent.application.service;

import com.huawei.it.ex.one.intent.application.model.RouteMemoryContext;
import com.huawei.it.ex.one.intent.application.model.RouteMemoryRouteCommand;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.Map;

/** Application boundary for intent clarification and applied-route memory. */
public interface RouteMemoryService {
    String TRIGGER_FIRST_TURN = "first_turn";
    String TRIGGER_DOMAIN_REJECT = "domain_reject";
    String TRIGGER_CLARIFY_ANSWER = "clarify_answer";
    String TRIGGER_FALLBACK_FOLLOWUP = "fallback_followup";
    String TRIGGER_USER_CORRECTION = "user_correction";

    RouteMemoryContext loadForIntent(
            UserContext user, String sessionId, String routeTrigger,
            Map<String, Object> lastIntentRejectReason);

    int activeClarificationCount(UserContext user, String sessionId);

    int maxClarificationRounds();

    int maxRouteHistorySize();

    boolean isNewRouteDecision(RouteTarget route);

    void appendClarification(
            UserContext user, String sessionId, String sourceRunId,
            String interactionId, Map<String, Object> requestPayload);

    void appendRoute(RouteMemoryRouteCommand command);

    void recordRouteDecision(RouteMemoryRouteCommand command);

    void completeWithoutRoute(UserContext user, String sessionId);

    boolean latestRouteIsRelayFallback(UserContext user, String sessionId);

    Map<String, Object> routeHistory(RouteMemoryRouteCommand command);

    void foldActiveClarifications(UserContext user, String sessionId);
}
