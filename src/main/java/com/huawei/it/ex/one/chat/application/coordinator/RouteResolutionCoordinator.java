package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.runtime.application.model.RuntimeProviders;

import com.huawei.it.ex.one.chat.application.mapper.ChatIntentMapper;
import com.huawei.it.ex.one.chat.application.service.ChatDocumentService;
import com.huawei.it.ex.one.chat.domain.AttachmentRef;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.intent.application.model.MemoryContext;
import com.huawei.it.ex.one.document.application.model.UploadedDocument;
import com.huawei.it.ex.one.intent.application.model.RouteTarget;
import com.huawei.it.ex.one.intent.application.model.RouteType;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.RouteSignalResult;
import com.huawei.it.ex.one.intent.application.service.IntentDecisionService;
import com.huawei.it.ex.one.runtime.application.model.DomainAgentBindingCommand;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBindingResolution;
import com.huawei.it.ex.one.runtime.application.model.RuntimeSessionMode;
import com.huawei.it.ex.one.common.metadata.SelectedIntentContext;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Resolves the existing route and binding rules without executing a Runtime. */
@Component
public class RouteResolutionCoordinator {
    private final RuntimeBindingService runtimeBindingService;
    private final IntentDecisionService routeSignalService;
    private final ChatDocumentService documentService;

    public RouteResolutionCoordinator(RuntimeBindingService runtimeBindingService,
                                      IntentDecisionService routeSignalService,
                                      ChatDocumentService documentService) {
        this.runtimeBindingService = runtimeBindingService;
        this.routeSignalService = routeSignalService;
        this.documentService = documentService;
    }

    public void prepareInitial(InitialRoutePreparation preparation) {
        if (preparation.explicitDomainAgentId() != null) {
            RouteTarget route = RouteTarget.domainAgent(preparation.explicitDomainAgentId(), "front-selected", 1.0,
                    "front selected domain agent");
            RuntimeBinding binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                    preparation.user().tenantId(), preparation.user().ownerUserId(), preparation.session().id(),
                    preparation.runId(), preparation.runtimeBindingLeafId(), preparation.explicitDomainAgentId(),
                    "front-selected",
                    domainAgentBindingMetadata(route, null,
                            preparation.command() == null ? Map.of() : preparation.command().metadata())));
            preparation.routeRef().set(route);
            preparation.bindingRef().set(binding);
            preparation.runtimeSessionModeRef().set(RuntimeSessionMode.RESUME);
            return;
        }
        if (preparation.forceReroute()) {
            runtimeBindingService.cancelActive(preparation.user().tenantId(), preparation.user().ownerUserId(),
                    preparation.session().id());
            return;
        }
        runtimeBindingService.findActiveBySession(preparation.user().tenantId(), preparation.user().ownerUserId(),
                        preparation.session().id())
                .ifPresent(active -> {
                    RuntimeBinding binding = runtimeBindingService.touchForRun(active, preparation.runId());
                    RouteTarget route = RuntimeProviders.DOMAIN_AGENT.equals(binding.provider())
                            ? RouteTarget.domainAgent(domainAgentId(binding), "runtime-binding", 1.0,
                            "active domain agent binding")
                            : RouteTarget.agentRuntime("runtime-binding", 1.0,
                            "active relay runtime binding");
                    preparation.bindingRef().set(binding);
                    preparation.routeRef().set(route);
                    preparation.runtimeSessionModeRef().set(RuntimeSessionMode.RESUME);
                });
    }

    public RouteExecutionResolution resolve(RouteResolutionRequest request,
                                            RouteSignalResult routeSignalResult) {
        UserContext user = request.user();
        ChatSession session = request.session();
        String runId = request.runId();
        String runtimeBindingLeafId = request.runtimeBindingLeafId();
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
                    ? routeSignalService.routeInitial(ChatIntentMapper.toRouteRequest(
                    new ChatIntentMapper.RouteRequestInput(
                            null, user, session, request.runCommand(), request.attachments(), request.memory(),
                            request.runCommand() == null ? null : request.runCommand().message())))
                    : routeSignalResult;
            route = routeSignal.route();
            intent = routeSignal.intentDecision();
            intentLatencyMs = routeSignal.intentLatencyMs();
            intentConfidenceThreshold = routeSignal.intentConfidenceThreshold();
            waitingIntentClarification = routeSignal.waitingIntentClarification();
            intentClarificationPayload = routeSignal.intentClarificationPayload();
            if (route == null) {
                route = RouteTarget.agentRuntime("fallback", 0.0, "route resolution returned empty route");
            } else if (!waitingIntentClarification && route.type() == RouteType.DOMAIN_AGENT) {
                binding = runtimeBindingService.bindDomainAgentForRun(new DomainAgentBindingCommand(
                        user.tenantId(), user.ownerUserId(), session.id(), runId,
                        runtimeBindingLeafId, route.selectedAgentCode(), route.routeSource(),
                        domainAgentBindingMetadata(route, intent)));
            } else if (!waitingIntentClarification && route.type() == RouteType.AGENT_RUNTIME) {
                RuntimeBindingResolution resolution = runtimeBindingService.resolveForRun(user.tenantId(),
                        user.ownerUserId(), session.id(), runId, runtimeBindingLeafId);
                binding = resolution.binding();
                runtimeSessionMode = resolution.sessionMode();
            }
        }
        if (route == null) {
            route = RouteTarget.agentRuntime("fallback", 0.0, "route resolution returned empty route");
        }
        return new RouteExecutionResolution(route, binding, runtimeSessionMode, intent, intentLatencyMs,
                intentConfidenceThreshold, waitingIntentClarification, intentClarificationPayload);
    }

    public ChatCommand runtimeCommand(RuntimeCommandRequest request) {
        ChatCommand command = withFoldedQuery(request.command(), request.routeMemoryQuery());
        return withIntentDocuments(command, request.documents(), request.resolvedRoute(),
                request.resolvedIntent(), request.metadataOverride());
    }

    public Map<String, Object> domainAgentBindingMetadata(RouteTarget route, IntentDecision intent) {
        return domainAgentBindingMetadata(route, intent, Map.of());
    }

    public Map<String, Object> domainAgentBindingMetadata(RouteTarget route, IntentDecision intent,
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

    public String domainAgentId(RuntimeBinding binding) {
        if (binding == null || binding.metadata() == null) {
            return null;
        }
        Object value = binding.metadata().get("domainAgentId");
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private ChatCommand withFoldedQuery(ChatCommand command, String foldedQuery) {
        if (command == null || command.metadata() == null
                || !command.metadata().containsKey("intentClarification")
                || foldedQuery == null || foldedQuery.isBlank()) {
            return command;
        }
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), foldedQuery, command.attachments(), command.metadata(),
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), command.routeTrigger(),
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName());
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
        Map<String, Object> runtimeMetadata = documentService.replaceRuntimeDocumentMetadata(
                metadataSource, documents);
        return new ChatCommand(command.commandId(), command.tenantId(), command.userId(), command.sessionId(),
                command.conversationId(), command.channel(), command.message(), command.attachments(), runtimeMetadata,
                command.targetType(), command.targetId(), command.runMode(), command.parentMessageId(),
                command.editedMessageId(), command.regeneratedMessageId(), command.routeTrigger(),
                command.interactionId(), command.approved(), command.scope(), command.questionnaireAnswers(),
                command.appId(), command.appName());
    }

    private boolean runtimeRoute(RouteTarget route) {
        return route != null && (route.type() == RouteType.DOMAIN_AGENT || route.type() == RouteType.AGENT_RUNTIME);
    }

    private void putIfNotNull(Map<String, Object> payload, String key, Object value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    public record InitialRoutePreparation(
            UserContext user,
            ChatSession session,
            String runId,
            String runtimeBindingLeafId,
            ChatCommand command,
            String explicitDomainAgentId,
            boolean forceReroute,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef
    ) {
    }

    public record RouteResolutionRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RouteTarget currentRoute,
            RuntimeBinding currentBinding,
            RuntimeSessionMode currentRuntimeSessionMode
    ) {
    }

    public record RouteExecutionResolution(
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

    public record RuntimeCommandRequest(
            ChatCommand command,
            String routeMemoryQuery,
            List<UploadedDocument> documents,
            RouteTarget resolvedRoute,
            IntentDecision resolvedIntent,
            Map<String, Object> metadataOverride
    ) {
    }
}
