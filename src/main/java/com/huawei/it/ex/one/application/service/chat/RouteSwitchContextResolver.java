package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves route-switch input, target and binding without changing workflow order. */
final class RouteSwitchContextResolver {
    private final RuntimeBindingApplicationService runtimeBindingService;

    RouteSwitchContextResolver(
            RuntimeBindingApplicationService runtimeBindingService) {
        this.runtimeBindingService = runtimeBindingService;
    }

    RouteSwitchInput input(
            ChatInteractionRequest interaction,
            ChatInteractionClaimResult claim) {
        boolean approved = Boolean.TRUE.equals(
                claim.responsePayload().get("approved"));
        String candidateProvider = blankToDefault(
                firstText(interaction.requestPayload().get("candidateProvider")),
                RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        String candidateTargetId = firstText(
                interaction.requestPayload().get("candidateTargetId"));
        String currentProvider = blankToDefault(
                firstText(interaction.requestPayload().get("currentProvider")),
                RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER);
        String currentTargetId = firstText(
                interaction.requestPayload().get("currentTargetId"));
        if (!RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(currentProvider)
                || currentTargetId == null
                || currentTargetId.isBlank()) {
            throw new IllegalStateException(
                    "路由切换 Interaction 缺少当前 DomainAgent 上下文");
        }
        String originalQuery = firstText(
                interaction.requestPayload().get("originalQuery"));
        String normalizedQuery = originalQuery == null ? "" : originalQuery;
        String candidateRouteQuery = blankToDefault(
                firstText(interaction.requestPayload().get("routeMemoryQuery")),
                normalizedQuery);
        return new RouteSwitchInput(
                approved,
                candidateProvider,
                candidateTargetId,
                currentTargetId,
                normalizedQuery,
                candidateRouteQuery);
    }

    RouteTarget target(
            ChatInteractionRequest interaction,
            RouteSwitchInput input) {
        if (!input.approved()) {
            return RouteTarget.domainAgent(
                    input.currentTargetId(),
                    routeSource(interaction),
                    1.0,
                    "declined route switch");
        }
        return approvedTarget(
                input.candidateProvider(),
                input.candidateTargetId(),
                "user-confirmed");
    }

    RouteSwitchBindingSelection selectBinding(
            ChatInteractionRequest interaction,
            RouteSwitchInput input,
            RouteSwitchBindingRequest request) {
        RuntimeSessionMode runtimeSessionMode = RuntimeSessionMode.RESUME;
        RuntimeBinding binding;
        if (!input.approved()) {
            binding = runtimeBindingService.resumeForInteraction(
                    interaction, request.runId(), request.agentMode());
        } else if (RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(
                input.candidateProvider())) {
            cancelCurrentBinding(interaction, request.runId());
            binding = runtimeBindingService.bindDomainAgentForRun(
                    new DomainAgentBindingCommand(
                            request.user().tenantId(),
                            request.user().ownerUserId(),
                            request.session().id(),
                            request.runId(),
                            interaction.assistantMessageId(),
                            input.candidateTargetId(),
                            "user-confirmed",
                            bindingMetadata(interaction),
                            request.agentMode()));
        } else if (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(
                input.candidateProvider())) {
            cancelCurrentBinding(interaction, request.runId());
            RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                    request.user().tenantId(),
                    request.user().ownerUserId(),
                    request.session().id(),
                    request.runId(),
                    interaction.assistantMessageId());
            binding = resolution.binding();
            runtimeSessionMode = resolution.sessionMode();
        } else {
            throw new IllegalArgumentException(
                    "不支持的候选 Runtime provider: " + input.candidateProvider());
        }
        return new RouteSwitchBindingSelection(binding, runtimeSessionMode);
    }

    private void cancelCurrentBinding(
            ChatInteractionRequest interaction,
            String runId) {
        runtimeBindingService.markNotRoutable(
                runtimeBindingService.resumeForInteraction(interaction, runId),
                firstText(interaction.requestPayload().get("refusalCode")));
    }

    private RouteTarget approvedTarget(
            String provider,
            String targetId,
            String routeSource) {
        if (RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(provider)) {
            if (targetId == null || targetId.isBlank()) {
                throw new IllegalArgumentException(
                        "切换到 DomainAgent 时 candidateTargetId 不能为空");
            }
            return RouteTarget.domainAgent(
                    targetId, routeSource, 1.0, "confirmed route switch");
        }
        if (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(provider)) {
            return RouteTarget.agentRuntime(
                    routeSource, 1.0, "confirmed route switch to relay");
        }
        throw new IllegalArgumentException(
                "不支持的候选 Runtime provider: " + provider);
    }

    private String routeSource(ChatInteractionRequest interaction) {
        return blankToDefault(
                firstText(interaction.requestPayload().get("currentRouteSource")),
                "front-selected");
    }

    private Map<String, Object> bindingMetadata(
            ChatInteractionRequest interaction) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        putIfNotNull(
                metadata,
                "domainAgentId",
                interaction.requestPayload().get("candidateTargetId"));
        metadata.put("routeSource", "user-confirmed");
        putIfNotNull(
                metadata,
                "intentCode",
                interaction.requestPayload().get("candidateIntentCode"));
        putIfNotNull(
                metadata,
                "intentName",
                interaction.requestPayload().get("candidateIntentName"));
        putIfNotNull(
                metadata,
                "confirmedFromDomainAgentId",
                interaction.requestPayload().get("currentTargetId"));
        metadata.put("confirmedInteractionId", interaction.id());
        return Map.copyOf(metadata);
    }

    private void putIfNotNull(
            Map<String, Object> payload,
            String key,
            Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private String firstText(Object value) {
        return value == null || String.valueOf(value).isBlank()
                ? null
                : String.valueOf(value);
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}

record RouteSwitchInput(
        boolean approved,
        String candidateProvider,
        String candidateTargetId,
        String currentTargetId,
        String originalQuery,
        String candidateRouteQuery
) {
}

record RouteSwitchBindingRequest(
        UserContext user,
        ChatSession session,
        String runId,
        AgentModeProfile agentMode
) {
}

record RouteSwitchBindingSelection(
        RuntimeBinding binding,
        RuntimeSessionMode sessionMode
) {
}
