/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.facade.FinanceChatFacade;
import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.AttachmentRef;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunMode;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Stable Finance chat facade.
 *
 * <p>Workflow details live in focused coordinators; this class only preserves
 * the public application entry.</p>
 */
@Service
public class FinanceEXChatService implements FinanceChatFacade {
    private final FinanceChatOrchestrator orchestrator;
    private final ChatRunAdmissionCoordinator admissionCoordinator;
    private final RuntimeBindingCacheSynchronizer cacheSynchronizer;
    private final DomainAgentRefusalCommitCoordinator refusalCommitCoordinator;
    private final ChatEventPipeline eventPipeline;

    @Autowired
    FinanceEXChatService(
            FinanceChatOrchestrator orchestrator,
            ChatRunAdmissionCoordinator admissionCoordinator,
            RuntimeBindingCacheSynchronizer cacheSynchronizer,
            DomainAgentRefusalCommitCoordinator refusalCommitCoordinator,
            ChatEventPipeline eventPipeline) {
        this.orchestrator = orchestrator;
        this.admissionCoordinator = admissionCoordinator;
        this.cacheSynchronizer = cacheSynchronizer;
        this.refusalCommitCoordinator = refusalCommitCoordinator;
        this.eventPipeline = eventPipeline;
    }

    @Autowired
    void setRunAdmissionCommitService(
            ChatRunAdmissionCommitService runAdmissionCommitService) {
        admissionCoordinator.setCommitService(runAdmissionCommitService);
    }

    @Autowired
    void setDomainAgentControlIoScheduler(
            @Qualifier("domainAgentControlIoScheduler") Scheduler scheduler) {
        cacheSynchronizer.setScheduler(scheduler);
        refusalCommitCoordinator.setControlIoScheduler(scheduler);
    }

    @Autowired
    void setChatEventBatcher(ChatEventBatcher chatEventBatcher) {
        eventPipeline.setBatcher(chatEventBatcher);
    }

    @Override
    public Mono<ChatRunStartResult> startRun(
            UserContext user,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        return startRun(user, TraceContext.empty(), command, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStartResult> startRun(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.startRun(user, traceContext, command, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStopResult> stopRun(
            UserContext user,
            String runId,
            RuntimeForwardHeaders forwardHeaders) {
        return stopRun(user, TraceContext.empty(), runId, forwardHeaders);
    }

    @Override
    public Mono<ChatRunStopResult> stopRun(
            UserContext user,
            TraceContext traceContext,
            String runId,
            RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.stopRun(user, traceContext, runId, forwardHeaders);
    }

    @Override
    public Flux<ChatEvent> executeRun(
            UserContext user,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        return executeRun(user, TraceContext.empty(), command, forwardHeaders);
    }

    @Override
    public Flux<ChatEvent> executeRun(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders) {
        return orchestrator.executeRun(user, traceContext, command, forwardHeaders);
    }

    static <T> Mono<T> withFirstEventTimeout(
            Mono<T> source,
            Duration timeout,
            Runnable abort) {
        return ChatRunStartCoordinator.withFirstEventTimeout(source, timeout, abort);
    }

    static String clarificationAnswerWithAttachments(
            String answerText,
            List<AttachmentRef> attachments) {
        return IntentClarificationContextAssembler.answerWithAttachments(
                answerText, attachments);
    }

    static String nextMessageWithAttachments(
            ChatRunMode runMode,
            String message,
            List<AttachmentRef> attachments) {
        if (runMode != ChatRunMode.NEXT
                || (message != null && !message.isBlank())) {
            return message;
        }
        return attachments == null || attachments.isEmpty() ? message : "";
    }
}
