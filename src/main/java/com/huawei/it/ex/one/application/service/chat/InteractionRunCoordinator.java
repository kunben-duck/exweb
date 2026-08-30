/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatInteractionType;
import com.huawei.it.ex.one.domain.chat.ChatSession;
import com.huawei.it.ex.one.domain.runtime.AgentModeProfile;

import reactor.core.publisher.Flux;

/** Selects the existing Interaction continuation workflow. */
final class InteractionRunCoordinator {
    private final SessionApplicationService sessionService;
    private final ChatInteractionApplicationService interactionService;
    private final IntentClarificationRunCoordinator intentClarificationCoordinator;
    private final AmbiguousRouteContinuationCoordinator ambiguousRouteCoordinator;
    private final RouteSwitchContinuationCoordinator routeSwitchCoordinator;
    private final RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator;

    InteractionRunCoordinator(
            SessionApplicationService sessionService,
            ChatInteractionApplicationService interactionService,
            IntentClarificationRunCoordinator intentClarificationCoordinator,
            AmbiguousRouteContinuationCoordinator ambiguousRouteCoordinator,
            RouteSwitchContinuationCoordinator routeSwitchCoordinator,
            RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator) {
        this.sessionService = sessionService;
        this.interactionService = interactionService;
        this.intentClarificationCoordinator = intentClarificationCoordinator;
        this.ambiguousRouteCoordinator = ambiguousRouteCoordinator;
        this.routeSwitchCoordinator = routeSwitchCoordinator;
        this.runtimeInteractionCoordinator = runtimeInteractionCoordinator;
    }

    InteractionRunCoordinator(
            SessionApplicationService sessionService,
            ChatInteractionApplicationService interactionService,
            IntentClarificationRunCoordinator intentClarificationCoordinator,
            RouteSwitchContinuationCoordinator routeSwitchCoordinator,
            RuntimeInteractionContinuationCoordinator runtimeInteractionCoordinator) {
        this(sessionService, interactionService, intentClarificationCoordinator, null,
                routeSwitchCoordinator, runtimeInteractionCoordinator);
    }

    Flux<ChatEvent> execute(Request request) {
        ChatInteractionRequest interaction = request.claim().request();
        ChatSession session = sessionService.getSession(
                request.user(), interaction.sessionId());
        if (request.startAttempt() != null && request.startAttempt().aborted()) {
            interactionService.markWaiting(interaction);
            return Flux.empty();
        }
        if (interaction.interactionType() == ChatInteractionType.INTENT_CLARIFICATION) {
            if (request.clarificationInput() == null) {
                throw new IllegalStateException(
                        "意图澄清 continuation 缺少可信附件解析结果");
            }
            if (request.ambiguousRoutePlan() != null
                    && request.ambiguousRoutePlan().selectedCandidate()) {
                if (ambiguousRouteCoordinator == null) {
                    throw new IllegalStateException(
                            "AMBIGUOUS_ROUTE continuation 协调器未配置");
                }
                return ambiguousRouteCoordinator.execute(
                        new AmbiguousRouteContinuationCoordinator.Request(
                                request.user(),
                                request.claim(),
                                request.runId(),
                                session,
                                request.forwardHeaders(),
                                request.traceContext(),
                                request.startAttempt(),
                                request.clarificationInput(),
                                request.ambiguousRoutePlan()));
            }
            return intentClarificationCoordinator.execute(
                    new IntentClarificationRunCoordinator.Request(
                            request.user(),
                            request.claim(),
                            request.runId(),
                            session,
                            request.forwardHeaders(),
                            request.traceContext(),
                            request.startAttempt(),
                            request.clarificationInput()));
        }
        if (interaction.interactionType()
                == ChatInteractionType.ROUTE_SWITCH_CONFIRMATION) {
            return routeSwitchCoordinator.execute(
                    new RouteSwitchContinuationCoordinator.Request(
                            request.user(),
                            request.claim(),
                            request.runId(),
                            session,
                            request.forwardHeaders(),
                            request.traceContext(),
                            request.startAttempt(),
                            request.agentMode(),
                            request.intentAccessName(),
                            request.routeSwitchAttachments()));
        }
        return runtimeInteractionCoordinator.execute(
                new RuntimeInteractionContinuationCoordinator.Request(
                        request.user(),
                        request.claim(),
                        request.runId(),
                        session,
                        request.forwardHeaders(),
                        request.traceContext(),
                        request.startAttempt(),
                        request.agentMode()));
    }

    record Request(
            UserContext user,
            ChatInteractionClaimResult claim,
            String runId,
            RuntimeForwardHeaders forwardHeaders,
            TraceContext traceContext,
            RunStartAttempt startAttempt,
            IntentClarificationContextAssembler.ContinuationInput clarificationInput,
            AgentModeProfile agentMode,
            String intentAccessName,
            AmbiguousRouteContinuationPlan ambiguousRoutePlan,
            ResolvedChatAttachments routeSwitchAttachments
    ) {
        Request {
            routeSwitchAttachments = routeSwitchAttachments == null
                    ? ResolvedChatAttachments.empty()
                    : routeSwitchAttachments;
        }

        Request(
                UserContext user,
                ChatInteractionClaimResult claim,
                String runId,
                RuntimeForwardHeaders forwardHeaders,
                TraceContext traceContext,
                RunStartAttempt startAttempt,
                IntentClarificationContextAssembler.ContinuationInput clarificationInput,
                AgentModeProfile agentMode) {
            this(user, claim, runId, forwardHeaders, traceContext, startAttempt,
                    clarificationInput, agentMode, null, null, ResolvedChatAttachments.empty());
        }
    }
}
