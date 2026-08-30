package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.routing.RelayOutputMode;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RuntimeProfile;
import com.huawei.it.ex.one.domain.routing.SensitiveInformationAccessNameResolver;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resolves route-switch input, target and binding without changing workflow order. */
final class RouteSwitchContextResolver {
    private static final String NO_MATCH = "NO_MATCH";
    private final RuntimeBindingApplicationService runtimeBindingService;
    private final SensitiveInformationAccessNameResolver sensitiveInformationResolver;

    RouteSwitchContextResolver(
            RuntimeBindingApplicationService runtimeBindingService) {
        this(runtimeBindingService, new SensitiveInformationAccessNameResolver(""));
    }

    RouteSwitchContextResolver(
            RuntimeBindingApplicationService runtimeBindingService,
            SensitiveInformationAccessNameResolver sensitiveInformationResolver) {
        this.runtimeBindingService = runtimeBindingService;
        this.sensitiveInformationResolver = sensitiveInformationResolver == null
                ? new SensitiveInformationAccessNameResolver("")
                : sensitiveInformationResolver;
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
        String routeAction = firstText(interaction.requestPayload().get("routeAction"));
        String candidateAccessName = firstText(
                interaction.requestPayload().get("candidateAccessName"));
        RuntimeProfile candidateRuntimeProfile = relayRuntimeProfile(
                candidateProvider,
                routeAction,
                candidateAccessName);
        RelayOutputMode candidateRelayOutputMode = relayOutputMode(
                candidateProvider,
                routeAction,
                candidateAccessName);
        String candidateRuntimeRoleName = firstText(
                interaction.requestPayload().get("candidateRuntimeRoleName"));
        if (candidateRuntimeProfile == RuntimeProfile.DOMAIN_EXPERT
                && candidateRuntimeRoleName == null) {
            throw new IllegalStateException(
                    "路由切换 Interaction 缺少 Relay 专家 roleName");
        }
        return new RouteSwitchInput(
                approved,
                candidateProvider,
                candidateTargetId,
                candidateRuntimeProfile,
                candidateRuntimeRoleName,
                candidateRelayOutputMode,
                invocationSkillId(candidateProvider, candidateTargetId, routeAction, candidateAccessName),
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
        return approvedTarget(input, "user-confirmed");
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
            binding = runtimeBindingService.switchDomainAgentForInteraction(
                    interaction,
                    new DomainAgentBindingCommand(
                            request.user().tenantId(),
                            request.user().ownerUserId(),
                            request.session().id(),
                            request.runId(),
                            interaction.assistantMessageId(),
                            input.candidateTargetId(),
                            "user-confirmed",
                            bindingMetadata(interaction),
                            request.agentMode()),
                    request.executionClaim());
            runtimeBindingService.synchronizeDeferredDomainAgentActivation(binding);
        } else if (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(
                input.candidateProvider())) {
            cancelCurrentBinding(interaction, request.runId());
            RuntimeBindingResolution resolution = runtimeBindingService.resolveForProfile(
                    new RuntimeBindingApplicationService.ProfiledRunBindingRequest(
                            request.user().tenantId(),
                            request.user().ownerUserId(),
                            request.session().id(),
                            request.runId(),
                            interaction.assistantMessageId(),
                            input.candidateRuntimeProfile(),
                            input.candidateRuntimeRoleName()));
            binding = resolution.binding();
            runtimeSessionMode = resolution.sessionMode();
        } else {
            throw new IllegalArgumentException(
                    "不支持的候选 Runtime provider: " + input.candidateProvider());
        }
        return new RouteSwitchBindingSelection(binding, runtimeSessionMode);
    }

    DeferredDomainAgentBinding prepareDomainAgentBindingForUnsupportedAttachment(
            ChatInteractionRequest interaction,
            RouteSwitchInput input,
            RouteSwitchBindingRequest request) {
        if (!input.approved()
                || !RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(
                        input.candidateProvider())) {
            throw new IllegalArgumentException(
                    "附件类型拒绝只支持已批准的 DomainAgent 路由切换");
        }
        return runtimeBindingService.prepareDomainAgentForRun(new DomainAgentBindingCommand(
                request.user().tenantId(),
                request.user().ownerUserId(),
                request.session().id(),
                request.runId(),
                interaction.assistantMessageId(),
                input.candidateTargetId(),
                "user-confirmed",
                bindingMetadata(interaction),
                request.agentMode()));
    }

    private void cancelCurrentBinding(
            ChatInteractionRequest interaction,
            String runId) {
        runtimeBindingService.markNotRoutable(
                runtimeBindingService.resumeForInteraction(interaction, runId),
                firstText(interaction.requestPayload().get("refusalCode")));
    }

    private RouteTarget approvedTarget(RouteSwitchInput input, String routeSource) {
        if (RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(input.candidateProvider())) {
            if (input.candidateTargetId() == null || input.candidateTargetId().isBlank()) {
                throw new IllegalArgumentException(
                        "切换到 DomainAgent 时 candidateTargetId 不能为空");
            }
            return RouteTarget.domainAgent(
                    input.candidateTargetId(), routeSource, 1.0, "confirmed route switch");
        }
        if (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(input.candidateProvider())) {
            if (input.candidateRelayOutputMode() == RelayOutputMode.ANSWER_STREAM_ONLY) {
                return RouteTarget.agentRuntimeAnswerStreamOnly(
                        routeSource, 1.0, "confirmed route switch to relay",
                        input.invocationSkillId());
            }
            if (input.candidateRuntimeProfile() == RuntimeProfile.DOMAIN_EXPERT) {
                return RouteTarget.domainExpertRuntime(
                        routeSource, 1.0, "confirmed route switch to relay",
                        input.candidateRuntimeRoleName(), input.invocationSkillId());
            }
            return RouteTarget.agentRuntimeWithInvocationSkill(
                    routeSource, 1.0, "confirmed route switch to relay",
                    input.invocationSkillId());
        }
        throw new IllegalArgumentException(
                "不支持的候选 Runtime provider: " + input.candidateProvider());
    }

    private RuntimeProfile relayRuntimeProfile(String provider, String routeAction, String candidateAccessName) {
        if (RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(provider)
                && isRouteSingle(routeAction)
                && !sensitiveInformationResolver.matches(candidateAccessName)) {
            return RuntimeProfile.DOMAIN_EXPERT;
        }
        return RuntimeProfile.DELEGATE;
    }

    private RelayOutputMode relayOutputMode(String provider, String routeAction, String candidateAccessName) {
        return RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(provider)
                && isRouteSingle(routeAction)
                && sensitiveInformationResolver.matches(candidateAccessName)
                ? RelayOutputMode.ANSWER_STREAM_ONLY
                : RelayOutputMode.FULL_STREAM;
    }

    private boolean isRouteSingle(String routeAction) {
        return "ROUTE_SINGLE".equalsIgnoreCase(routeAction);
    }

    private String invocationSkillId(
            String provider,
            String targetId,
            String routeAction,
            String accessName) {
        if (RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(provider)) {
            return targetId;
        }
        if (!RuntimeBindingApplicationService.DEFAULT_RUNTIME_PROVIDER.equals(provider)) {
            return null;
        }
        if (NO_MATCH.equalsIgnoreCase(routeAction)) {
            return NO_MATCH;
        }
        return isRouteSingle(routeAction) ? accessName : null;
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
        RuntimeProfile candidateRuntimeProfile,
        String candidateRuntimeRoleName,
        RelayOutputMode candidateRelayOutputMode,
        String invocationSkillId,
        String currentTargetId,
        String originalQuery,
        String candidateRouteQuery
) {
}

record RouteSwitchBindingRequest(
        UserContext user,
        ChatSession session,
        String runId,
        AgentModeProfile agentMode,
        RunExecutionClaim executionClaim
) {
}

record RouteSwitchBindingSelection(
        RuntimeBinding binding,
        RuntimeSessionMode sessionMode
) {
}
