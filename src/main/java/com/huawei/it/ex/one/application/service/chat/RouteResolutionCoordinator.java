package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.integration.agent.SelectedIntentContext;
import com.huawei.it.ex.one.application.service.memory.ShortTermMemoryContextAssembler;
import com.huawei.it.ex.one.application.service.routing.RouteSignalApplicationService;
import com.huawei.it.ex.one.application.service.routing.RouteSignalResult;
import com.huawei.it.ex.one.application.service.runtime.DomainAgentBindingCommand;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingResolution;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.routing.RouteType;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Resolves the existing route and binding rules without executing a Runtime. */
final class RouteResolutionCoordinator {
    static final String DOMAIN_AGENT_REROUTE_CONTEXT_METADATA = "domainAgentRerouteContext";

    private final RuntimeBindingApplicationService runtimeBindingService;
    private final RouteSignalApplicationService routeSignalService;
    private final DocumentFacade documentFacade;

    RouteResolutionCoordinator(RuntimeBindingApplicationService runtimeBindingService,
                               RouteSignalApplicationService routeSignalService,
                               DocumentFacade documentFacade) {
        this.runtimeBindingService = runtimeBindingService;
        this.routeSignalService = routeSignalService;
        this.documentFacade = documentFacade;
    }

    void prepareInitial(InitialRoutePreparation preparation) {
        if (preparation.explicitDomainAgentId() != null) {
            RouteTarget route = RouteTarget.domainAgent(preparation.explicitDomainAgentId(), "front-selected", 1.0,
                    "front selected domain agent");
            RuntimeBinding binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                    preparation.user().tenantId(),
                    preparation.user().ownerUserId(),
                    preparation.session().id(),
                    preparation.runId(),
                    preparation.runtimeBindingLeafId(),
                    preparation.explicitDomainAgentId(),
                    "front-selected",
                    domainAgentBindingMetadata(route, null,
                            preparation.command() == null ? Map.of() : preparation.command().metadata()),
                    preparation.agentMode()));
            preparation.routeRef().set(route);
            preparation.bindingRef().set(binding);
            preparation.runtimeSessionModeRef().set(RuntimeSessionMode.RESUME);
            preparation.bindingLifecycle().trackCreated(binding);
            return;
        }
        if (preparation.forceReroute()) {
            runtimeBindingService.cancelActive(
                    preparation.user().tenantId(),
                    preparation.user().ownerUserId(),
                    preparation.session().id());
            return;
        }
        runtimeBindingService.findActiveBySession(
                        preparation.user().tenantId(),
                        preparation.user().ownerUserId(),
                        preparation.session().id())
                .ifPresent(active -> restoreActiveBinding(preparation, active));
    }

    RouteExecutionResolution resolve(RouteResolutionRequest request,
                                     RouteSignalResult routeSignalResult) {
        UserContext user = request.user();
        ChatSession session = request.session();
        RouteTarget route = request.currentRoute();
        RuntimeBinding binding = request.currentBinding();
        RuntimeSessionMode runtimeSessionMode = request.currentRuntimeSessionMode() == null
                ? RuntimeSessionMode.RESUME
                : request.currentRuntimeSessionMode();
        IntentDecision intent = null;
        Long intentLatencyMs = null;
        Double intentConfidenceThreshold = null;
        boolean waitingIntentClarification = false;
        Map<String, Object> intentClarificationPayload = Map.of();

        if (route == null) {
            RouteSignalResult routeSignal = routeSignalResult == null
                    ? routeSignalService.routeInitial(
                            user,
                            session,
                            request.runCommand(),
                            request.attachments(),
                            request.memory())
                    : routeSignalResult;
            route = routeSignal.route();
            intent = routeSignal.intentDecision();
            intentLatencyMs = routeSignal.intentLatencyMs();
            intentConfidenceThreshold = routeSignal.intentConfidenceThreshold();
            waitingIntentClarification = routeSignal.waitingIntentClarification();
            intentClarificationPayload = routeSignal.intentClarificationPayload();
            BindingResolution resolution = bindResolvedRoute(request, route, intent, waitingIntentClarification);
            route = resolution.route();
            binding = resolution.binding();
            runtimeSessionMode = resolution.runtimeSessionMode();
        }
        if (route == null) {
            route = fallbackRoute();
        }
        return new RouteExecutionResolution(
                route,
                binding,
                runtimeSessionMode,
                intent,
                intentLatencyMs,
                intentConfidenceThreshold,
                waitingIntentClarification,
                intentClarificationPayload);
    }

    ChatCommand runtimeCommand(RuntimeCommandRequest request) {
        ChatCommand command = withoutPrivateIntentMemory(
                withFoldedQuery(request.command(), request.routeMemoryQuery()));
        return withIntentDocuments(
                command,
                request.documents(),
                request.resolvedRoute(),
                request.resolvedIntent(),
                request.metadataOverride());
    }

    ChatCommand withoutDomainAgentRerouteContext(ChatCommand command) {
        if (command == null || command.metadata() == null
                || (!command.metadata().containsKey(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA)
                && !command.metadata().containsKey(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY))) {
            return command;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        // 拒答重路由上下文仅供 Intent 使用，目标确定后不得透传给 Runtime。
        metadata.remove(DOMAIN_AGENT_REROUTE_CONTEXT_METADATA);
        metadata.remove(DomainAgentRejectReason.METADATA_KEY);
        metadata.remove(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY);
        return copyCommand(command, command.message(), metadata);
    }

    private ChatCommand withoutPrivateIntentMemory(ChatCommand command) {
        if (command == null || command.metadata() == null
                || !command.metadata().containsKey(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY)) {
            return command;
        }
        Map<String, Object> metadata = new LinkedHashMap<>(command.metadata());
        metadata.remove(ShortTermMemoryContextAssembler.PRIVATE_INTENT_MESSAGES_KEY);
        return copyCommand(command, command.message(), metadata);
    }

    Map<String, Object> domainAgentBindingMetadata(RouteTarget route, IntentDecision intent) {
        return domainAgentBindingMetadata(route, intent, Map.of());
    }

    Map<String, Object> domainAgentBindingMetadata(RouteTarget route,
                                                   IntentDecision intent,
                                                   Map<String, Object> commandMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (route != null) {
            metadata.put("domainAgentId", route.selectedAgentCode());
            metadata.put("routeSource", route.routeSource());
        }
        if (intent != null) {
            metadata.put("intentCode", intent.intentCode());
            metadata.put("intentName", intent.intentName());
            metadata.put("intentConfidence", intent.confidence());
        } else {
            putIfNotNull(metadata, "intentCode", SelectedIntentContext.intentId(commandMetadata));
            putIfNotNull(metadata, "intentName", SelectedIntentContext.intentName(commandMetadata));
        }
        return Map.copyOf(metadata);
    }

    String domainAgentId(RuntimeBinding binding) {
        if (binding == null || binding.metadata() == null) {
            return null;
        }
        Object value = binding.metadata().get("domainAgentId");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private void restoreActiveBinding(InitialRoutePreparation preparation, RuntimeBinding active) {
        boolean domainAgent = RuntimeBindingApplicationService.DOMAIN_AGENT_PROVIDER.equals(active.provider());
        RuntimeBinding binding = domainAgent
                ? runtimeBindingService.touchDomainAgentForRun(
                        active,
                        preparation.runId(),
                        preparation.agentMode())
                : runtimeBindingService.touchForRun(active, preparation.runId());
        RouteTarget route = domainAgent
                ? RouteTarget.domainAgent(
                        domainAgentId(binding),
                        "runtime-binding",
                        1.0,
                        "active domain agent binding")
                : RouteTarget.agentRuntime(
                        "runtime-binding",
                        1.0,
                        "active relay runtime binding");
        preparation.bindingRef().set(binding);
        preparation.routeRef().set(route);
        preparation.runtimeSessionModeRef().set(RuntimeSessionMode.RESUME);
        preparation.bindingLifecycle().trackReused(binding, active);
    }

    private BindingResolution bindResolvedRoute(RouteResolutionRequest request,
                                                RouteTarget route,
                                                IntentDecision intent,
                                                boolean waitingIntentClarification) {
        if (route == null) {
            return new BindingResolution(fallbackRoute(), request.currentBinding(),
                    normalizedMode(request.currentRuntimeSessionMode()));
        }
        if (waitingIntentClarification) {
            return new BindingResolution(route, request.currentBinding(),
                    normalizedMode(request.currentRuntimeSessionMode()));
        }
        if (route.type() == RouteType.DOMAIN_AGENT) {
            RuntimeBinding binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                    request.user().tenantId(),
                    request.user().ownerUserId(),
                    request.session().id(),
                    request.runId(),
                    request.runtimeBindingLeafId(),
                    route.selectedAgentCode(),
                    route.routeSource(),
                    domainAgentBindingMetadata(route, intent),
                    request.agentMode()));
            request.bindingLifecycle().trackCreated(binding);
            return new BindingResolution(route, binding, normalizedMode(request.currentRuntimeSessionMode()));
        }
        if (route.type() == RouteType.AGENT_RUNTIME) {
            RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(
                    request.user().tenantId(),
                    request.user().ownerUserId(),
                    request.session().id(),
                    request.runId(),
                    request.runtimeBindingLeafId());
            if (resolution.previousBinding() == null) {
                request.bindingLifecycle().trackCreated(resolution.binding());
            } else {
                request.bindingLifecycle().trackReused(
                        resolution.binding(), resolution.previousBinding());
            }
            return new BindingResolution(route, resolution.binding(), resolution.sessionMode());
        }
        return new BindingResolution(route, request.currentBinding(),
                normalizedMode(request.currentRuntimeSessionMode()));
    }

    private ChatCommand withFoldedQuery(ChatCommand command, String foldedQuery) {
        if (command == null || command.metadata() == null
                || !command.metadata().containsKey("intentClarification")
                || foldedQuery == null || foldedQuery.isBlank()) {
            return command;
        }
        return copyCommand(command, foldedQuery, command.metadata());
    }

    private ChatCommand withIntentDocuments(ChatCommand command,
                                            List<UploadedDocument> documents,
                                            RouteTarget resolvedRoute,
                                            IntentDecision resolvedIntent,
                                            Map<String, Object> metadataOverride) {
        if (command == null || !runtimeRoute(resolvedRoute)) {
            return command;
        }
        Map<String, Object> metadataSource = metadataOverride != null
                ? metadataOverride
                : resolvedIntent == null ? null : command.metadata();
        if (metadataSource == null) {
            return command;
        }
        Map<String, Object> runtimeMetadata = documentFacade.replaceRuntimeDocumentMetadata(
                metadataSource,
                documents);
        return copyCommand(command, command.message(), runtimeMetadata);
    }

    private ChatCommand copyCommand(ChatCommand command, String message, Map<String, Object> metadata) {
        return new ChatCommand(
                command.commandId(),
                command.tenantId(),
                command.userId(),
                command.sessionId(),
                command.conversationId(),
                command.channel(),
                message,
                command.attachments(),
                metadata,
                command.targetType(),
                command.targetId(),
                command.runMode(),
                command.parentMessageId(),
                command.editedMessageId(),
                command.regeneratedMessageId(),
                command.routeTrigger(),
                command.interactionId(),
                command.approved(),
                command.scope(),
                command.questionnaireAnswers(),
                command.appId(),
                command.appName(),
                command.agentMode());
    }

    private boolean runtimeRoute(RouteTarget route) {
        return route != null
                && (route.type() == RouteType.DOMAIN_AGENT || route.type() == RouteType.AGENT_RUNTIME);
    }

    private RuntimeSessionMode normalizedMode(RuntimeSessionMode mode) {
        return mode == null ? RuntimeSessionMode.RESUME : mode;
    }

    private RouteTarget fallbackRoute() {
        return RouteTarget.agentRuntime("fallback", 0.0, "route resolution returned empty route");
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    record InitialRoutePreparation(
            UserContext user,
            ChatSession session,
            String runId,
            String runtimeBindingLeafId,
            ChatCommand command,
            String explicitDomainAgentId,
            boolean forceReroute,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            AgentModeProfile agentMode,
            RuntimeBindingDispatchLifecycle bindingLifecycle
    ) {
    }

    record RouteResolutionRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RouteTarget currentRoute,
            RuntimeBinding currentBinding,
            RuntimeSessionMode currentRuntimeSessionMode,
            AgentModeProfile agentMode,
            RuntimeBindingDispatchLifecycle bindingLifecycle
    ) {
    }

    record RouteExecutionResolution(
            RouteTarget route,
            RuntimeBinding binding,
            RuntimeSessionMode runtimeSessionMode,
            IntentDecision intent,
            Long intentLatencyMs,
            Double intentConfidenceThreshold,
            boolean waitingIntentClarification,
            Map<String, Object> intentClarificationPayload
    ) {
    }

    record RuntimeCommandRequest(
            ChatCommand command,
            String routeMemoryQuery,
            List<UploadedDocument> documents,
            RouteTarget resolvedRoute,
            IntentDecision resolvedIntent,
            Map<String, Object> metadataOverride
    ) {
    }

    private record BindingResolution(
            RouteTarget route,
            RuntimeBinding binding,
            RuntimeSessionMode runtimeSessionMode
    ) {
    }
}
