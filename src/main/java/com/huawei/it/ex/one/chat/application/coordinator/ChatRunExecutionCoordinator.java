package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatRunLeaseApplicationService;
import com.huawei.it.ex.one.chat.domain.ChatCommand;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.common.http.RuntimeForwardHeaders;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/** Thin coordinator for the existing standard run preparation, admission and execution phases. */
@Component
public class ChatRunExecutionCoordinator {
    private final StandardRunInputPreparer inputPreparer;
    private final StandardRunAdmissionCoordinator admissionCoordinator;
    private final StandardRunRuntimeCoordinator runtimeCoordinator;
    private final ChatRunLeaseApplicationService chatRunLeaseService;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final ChatRunFailureCoordinator failureCoordinator;

    public ChatRunExecutionCoordinator(StandardRunInputPreparer inputPreparer,
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

    public Flux<ChatEvent> execute(Request request) {
        return Flux.defer(() -> {
            StandardRunInputPreparer.PreparedRun prepared = inputPreparer.prepare(
                    new StandardRunInputPreparer.Request(
                            request.user(), request.traceContext(), request.command(),
                            request.forwardHeaders(), request.startAttempt()),
                    true);
            StandardRunAdmissionCoordinator.Admission admission = admissionCoordinator.admit(prepared);
            StandardRunRuntimeCoordinator.RuntimePlan runtimePlan =
                    runtimeCoordinator.prepare(prepared, admission);
            RunExecutionClaim executionClaim;
            try {
                executionClaim = chatRunLeaseService.startRun(admission.run());
            } catch (RuntimeException ex) {
                return failureCoordinator.failExecutionInitialization(admission.run(), null, ex);
            }
            runStartCoordinator.trackExecution(
                    request.startAttempt(), executionClaim, "after-execution-create");
            return runtimeCoordinator.execute(runtimePlan, executionClaim);
        });
    }

    public record Request(
            UserContext user,
            TraceContext traceContext,
            ChatCommand command,
            RuntimeForwardHeaders forwardHeaders,
            RunStartAttempt startAttempt
    ) {
    }
}
