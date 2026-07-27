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
            StandardRunInputPreparer.PreparedRun prepared = inputPreparer.prepare(
                    new StandardRunInputPreparer.Request(
                            request.user(),
                            request.traceContext(),
                            request.command(),
                            request.forwardHeaders(),
                            request.startAttempt()));
            StandardRunAdmissionCoordinator.Admission admission =
                    admissionCoordinator.admit(prepared);
            StandardRunRuntimeCoordinator.RuntimePlan runtimePlan =
                    runtimeCoordinator.prepare(prepared, admission);
            RunExecutionClaim executionClaim;
            try {
                executionClaim = chatRunLeaseService.startRun(admission.run());
            } catch (RuntimeException ex) {
                return failureCoordinator.failExecutionInitialization(
                        admission.run(), null, ex);
            }
            runStartCoordinator.trackExecution(
                    request.startAttempt(),
                    executionClaim,
                    "after-execution-create");
            return runtimeCoordinator.execute(runtimePlan, executionClaim);
        });
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
