package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.it.ex.one.application.service.agentdatapersistence.AgentDataPersistenceState;
import com.huawei.it.ex.one.application.service.runtime.DeferredDomainAgentBinding;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;
import com.huawei.it.ex.one.domain.document.UploadedDocument;
import com.huawei.it.ex.one.domain.memory.MemoryContext;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Immutable inputs for one Intent route and Runtime dispatch pass. */
record RoutePipelineRequest(
        UserContext user,
        ChatSession session,
        ChatCommand runCommand,
        List<AttachmentRef> attachments,
        List<UploadedDocument> documents,
        MemoryContext memory,
        String runId,
        String runtimeBindingLeafId,
        RuntimeForwardHeaders forwardHeaders,
        TraceContext traceContext,
        AtomicReference<RouteTarget> routeRef,
        AtomicReference<RuntimeBinding> bindingRef,
        AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
        RunExecutionClaim executionClaim,
        ChatRun run,
        String routeMemoryQuery,
        String intentQuery,
        String intentRouteMemoryQuery,
        Map<String, Object> runtimeMetadataOverride,
        AgentModeProfile agentMode,
        RuntimeBindingDispatchLifecycle bindingLifecycle,
        AgentDataPersistenceState persistenceState,
        MessageSkillTracker messageSkill,
        AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
        AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef,
        AtomicReference<PendingRouteMemoryDecision> pendingRouteMemoryDecisionRef
) {
    RoutePipelineRequest {
        persistenceState = persistenceState == null
                ? AgentDataPersistenceState.full()
                : persistenceState;
        pendingInteractionPayloadRef = pendingInteractionPayloadRef == null
                ? new AtomicReference<>()
                : pendingInteractionPayloadRef;
        messageSkill = messageSkill == null ? new MessageSkillTracker() : messageSkill;
        deferredDomainAgentBindingRef = deferredDomainAgentBindingRef == null
                ? new AtomicReference<>()
                : deferredDomainAgentBindingRef;
        pendingRouteMemoryDecisionRef = pendingRouteMemoryDecisionRef == null
                ? new AtomicReference<>()
                : pendingRouteMemoryDecisionRef;
    }

    RoutePipelineRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            RunExecutionClaim executionClaim,
            ChatRun run,
            String routeMemoryQuery,
            String intentQuery,
            String intentRouteMemoryQuery,
            Map<String, Object> runtimeMetadataOverride,
            AgentModeProfile agentMode,
            RuntimeBindingDispatchLifecycle bindingLifecycle,
            AgentDataPersistenceState persistenceState) {
        this(user, session, runCommand, attachments, documents, memory, runId, runtimeBindingLeafId,
                forwardHeaders, traceContext, routeRef, bindingRef, runtimeSessionModeRef, executionClaim,
                run, routeMemoryQuery, intentQuery, intentRouteMemoryQuery, runtimeMetadataOverride,
                agentMode, bindingLifecycle, persistenceState, new MessageSkillTracker(), new AtomicReference<>(),
                new AtomicReference<>(), new AtomicReference<>());
    }

    RoutePipelineRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            RunExecutionClaim executionClaim,
            ChatRun run,
            String routeMemoryQuery,
            String intentQuery,
            String intentRouteMemoryQuery,
            Map<String, Object> runtimeMetadataOverride,
            AgentModeProfile agentMode,
            RuntimeBindingDispatchLifecycle bindingLifecycle) {
        this(user, session, runCommand, attachments, documents, memory, runId, runtimeBindingLeafId,
                forwardHeaders, traceContext, routeRef, bindingRef, runtimeSessionModeRef, executionClaim,
                run, routeMemoryQuery, intentQuery, intentRouteMemoryQuery, runtimeMetadataOverride,
                agentMode, bindingLifecycle, AgentDataPersistenceState.full(), new MessageSkillTracker(),
                new AtomicReference<>(), new AtomicReference<>(), new AtomicReference<>());
    }

    RoutePipelineRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            RunExecutionClaim executionClaim,
            ChatRun run,
            String routeMemoryQuery,
            String intentQuery,
            String intentRouteMemoryQuery,
            Map<String, Object> runtimeMetadataOverride,
            AgentModeProfile agentMode,
            RuntimeBindingDispatchLifecycle bindingLifecycle,
            AgentDataPersistenceState persistenceState,
            MessageSkillTracker messageSkill,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef) {
        this(user, session, runCommand, attachments, documents, memory, runId, runtimeBindingLeafId,
                forwardHeaders, traceContext, routeRef, bindingRef, runtimeSessionModeRef, executionClaim,
                run, routeMemoryQuery, intentQuery, intentRouteMemoryQuery, runtimeMetadataOverride,
                agentMode, bindingLifecycle, persistenceState, messageSkill, pendingInteractionPayloadRef,
                new AtomicReference<>(), new AtomicReference<>());
    }

    RoutePipelineRequest(
            UserContext user,
            ChatSession session,
            ChatCommand runCommand,
            List<AttachmentRef> attachments,
            List<UploadedDocument> documents,
            MemoryContext memory,
            String runId,
            String runtimeBindingLeafId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            AtomicReference<RouteTarget> routeRef,
            AtomicReference<RuntimeBinding> bindingRef,
            AtomicReference<RuntimeSessionMode> runtimeSessionModeRef,
            RunExecutionClaim executionClaim,
            ChatRun run,
            String routeMemoryQuery,
            String intentQuery,
            String intentRouteMemoryQuery,
            Map<String, Object> runtimeMetadataOverride,
            AgentModeProfile agentMode,
            RuntimeBindingDispatchLifecycle bindingLifecycle,
            AgentDataPersistenceState persistenceState,
            MessageSkillTracker messageSkill,
            AtomicReference<Map<String, Object>> pendingInteractionPayloadRef,
            AtomicReference<DeferredDomainAgentBinding> deferredDomainAgentBindingRef) {
        this(user, session, runCommand, attachments, documents, memory, runId, runtimeBindingLeafId,
                forwardHeaders, traceContext, routeRef, bindingRef, runtimeSessionModeRef, executionClaim,
                run, routeMemoryQuery, intentQuery, intentRouteMemoryQuery, runtimeMetadataOverride,
                agentMode, bindingLifecycle, persistenceState, messageSkill, pendingInteractionPayloadRef,
                deferredDomainAgentBindingRef, new AtomicReference<>());
    }
}
