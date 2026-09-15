/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.DocumentFacade;
import com.huawei.it.ex.one.application.facade.ResolvedChatAttachments;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.memory.MemoryContext;

import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** 问卷答案流的 DomainAgent 拒答适配；附件与短期记忆只在真实拒答后回源。 */
final class DomainAgentQuestionnaireContinuation {
    private final DomainAgentRefusalCoordinator refusalCoordinator;
    private final DocumentFacade documentFacade;
    private final RunMemoryContextAssembler memoryAssembler;
    private final SessionApplicationService sessionService;

    DomainAgentQuestionnaireContinuation(DomainAgentRefusalCoordinator refusalCoordinator,
                                         DocumentFacade documentFacade, RunMemoryContextAssembler memoryAssembler,
                                         SessionApplicationService sessionService) {
        this.refusalCoordinator = refusalCoordinator;
        this.documentFacade = documentFacade;
        this.memoryAssembler = memoryAssembler;
        this.sessionService = sessionService;
    }

    Flux<ChatEvent> execute(RunEventPipelineContext pipeline, ChatInteractionRequest interaction,
                            Map<String, Object> response, RuntimeForwardHeaders headers,
                            TraceContext trace, Flux<ChatEvent> source) {
        Map<String, Object> snapshot = DomainAgentQuestionnaireContext.require(interaction);
        ChatCommand command = DomainAgentQuestionnaireContext.command(interaction, response, pipeline.runId());
        int reroutes = snapshot.get("rerouteCount") instanceof Number count ? count.intValue() : 0;
        DomainAgentRunContext context = new DomainAgentRunContext(command, pipeline.runId(),
                interaction.userMessageId(), pipeline.session(), MemoryContext.empty(), pipeline.routeRef().get(),
                pipeline.user(), pipeline.routeRef(), pipeline.bindingRef(), pipeline.executionClaim(), headers, trace,
                null, List.of(), Set.copyOf(DomainAgentQuestionnaireContext.strings(snapshot.get("rejectedDomainAgentIds"))),
                reroutes, command.message(), pipeline.assistant().persistenceState(), pipeline.assistant().messageSkill(),
                pipeline.pendingInteractionPayloadRef(), pipeline.deferredDomainAgentBindingRef(),
                pipeline.pendingRouteMemoryDecisionRef());
        return refusalCoordinator.execute(context, source, this::prepareReroute);
    }

    void prepareAssistant(RunEventPipelineContext pipeline, ChatInteractionRequest interaction) {
        if (!pipeline.assistant().persistenceState().placeholderMode()) {
            pipeline.assistant().seedQuestionnaireContent(sessionService.questionnaireAssistantContent(
                    pipeline.user(), interaction.sessionId(), interaction.assistantMessageId()));
        }
    }

    private DomainAgentRunContext prepareReroute(DomainAgentRunContext context) {
        ResolvedChatAttachments attachments = context.command().attachments().isEmpty()
                ? ResolvedChatAttachments.empty()
                : documentFacade.resolveChatAttachmentsForUser(context.user(), context.command().attachments());
        Map<String, Object> metadata = attachments.documents().isEmpty() ? context.command().metadata()
                : documentFacade.replaceRuntimeDocumentMetadata(context.command().metadata(), attachments.documents());
        ChatCommand command = DomainAgentQuestionnaireContext.withDocuments(
                context.command(), attachments.attachments(), metadata);
        MemoryContext memory = memoryAssembler.assemble(command, context.userMessageId(), context.userMessageId());
        return new DomainAgentRunContext(command, context.runId(), context.userMessageId(), context.session(), memory,
                context.route(), context.user(), context.routeRef(), context.bindingRef(), context.executionClaim(),
                context.forwardHeaders(), context.traceContext(), context.intentDecision(), attachments.documents(),
                context.rejectedDomainAgentIds(), context.rerouteCount(), command.message(), context.persistenceState(),
                context.messageSkill(), context.pendingInteractionPayloadRef(), context.deferredDomainAgentBindingRef(),
                context.pendingRouteMemoryDecisionRef());
    }
}
