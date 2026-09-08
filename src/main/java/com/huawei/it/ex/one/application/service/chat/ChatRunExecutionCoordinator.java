/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import reactor.core.publisher.Flux;

/** Coordinates the existing standard run preparation, admission and execution phases. */
final class ChatRunExecutionCoordinator {
    private final StandardRunInputPreparer inputPreparer;
    private final StandardRunAdmissionCoordinator admissionCoordinator;
    private final StandardRunRuntimeCoordinator runtimeCoordinator;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatRunFailureCoordinator failureCoordinator;

    ChatRunExecutionCoordinator(StandardRunInputPreparer inputPreparer,
                                StandardRunAdmissionCoordinator admissionCoordinator,
                                StandardRunRuntimeCoordinator runtimeCoordinator,
                                ChatRunLeaseApplicationService chatRunLeaseService,
                                ChatRunStartCoordinator runStartCoordinator,
                                ChatRunFailureCoordinator failureCoordinator) {
        this.inputPreparer = inputPreparer;
        this.admissionCoordinator = admissionCoordinator;
        this.runtimeCoordinator = runtimeCoordinator;
        this.chatRunLeaseService = chatRunLeaseService;
        this.runStartCoordinator = runStartCoordinator;
        this.failureCoordinator = failureCoordinator;
    }

    Flux<ChatEvent> execute(Request request) {
        return Flux.defer(() -> {
            // 外部输入和记忆先准备；消息树、user 消息和 Run 的一致性由后续准入事务负责。
            StandardRunInputPreparer.PreparedRun prepared = inputPreparer.prepare(
                    new StandardRunInputPreparer.Request(
                            request.user(),
                            request.traceContext(),
                            request.command(),
                            request.forwardHeaders(),
                            request.startAttempt()));
            StandardRunAdmissionCoordinator.Admission admission =
                    admissionCoordinator.admit(prepared);
            return executePrepared(request, prepared, admission);
        });
    }

    Flux<ChatEvent> executeCandidateSwitch(
            Request request,
            CandidateSwitchRunSource source) {
        return Flux.defer(() -> {
            StandardRunInputPreparer.PreparedRun prepared = inputPreparer.prepareCandidateSwitch(
                    new StandardRunInputPreparer.Request(
                            request.user(),
                            request.traceContext(),
                            request.command(),
                            request.forwardHeaders(),
                            request.startAttempt()),
                    source);
            StandardRunAdmissionCoordinator.Admission admission =
                    admissionCoordinator.admitCandidateSwitch(prepared, source);
            return executePrepared(request, prepared, admission, source.routeTrace());
        });
    }

    private Flux<ChatEvent> executePrepared(
            Request request,
            StandardRunInputPreparer.PreparedRun prepared,
            StandardRunAdmissionCoordinator.Admission admission) {
        return executePrepared(request, prepared, admission, null);
    }

    private Flux<ChatEvent> executePrepared(
            Request request,
            StandardRunInputPreparer.PreparedRun prepared,
            StandardRunAdmissionCoordinator.Admission admission,
            CandidateSwitchRouteTrace routeTrace) {
        StandardRunRuntimeCoordinator.RuntimePlan runtimePlan =
                runtimeCoordinator.prepare(prepared, admission);
        RunExecutionClaim executionClaim;
        try {
            // Run 已受理后再领取执行租约；初始化失败走独立收口，不能启动无 owner 的 Runtime。
            executionClaim = chatRunLeaseService.startRun(admission.run());
        } catch (RuntimeException ex) {
            return failureCoordinator.failExecutionInitialization(
                    admission.run(), null, ex);
        }
        runStartCoordinator.trackExecution(
                request.startAttempt(),
                executionClaim,
                "after-execution-create");
        return routeTrace == null
                ? runtimeCoordinator.execute(runtimePlan, executionClaim)
                : runtimeCoordinator.executeCandidateSwitch(runtimePlan, executionClaim, routeTrace);
    }

    record Request(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders,
            RunStartAttempt startAttempt
    ) {
    }
}
