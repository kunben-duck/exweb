package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.chat.application.model.RunStartAttempt;
import com.huawei.it.ex.one.chat.application.service.ChatRunAdmissionCommitService;
import com.huawei.it.ex.one.chat.application.service.ChatRunApplicationService;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatRunMessagePlan;
import org.springframework.stereotype.Component;

/** Preserves the existing standard run admission selection and post-commit cache order. */
@Component
public class StandardRunAdmissionCoordinator {
    private final ChatRunApplicationService chatRunService;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final RuntimeBindingCacheSynchronizer cacheSynchronizer;
    private final ChatRunAdmissionCommitService runAdmissionCommitService;

    public StandardRunAdmissionCoordinator(ChatRunApplicationService chatRunService,
                                           ChatRunStartCoordinator runStartCoordinator,
                                           RuntimeBindingCacheSynchronizer cacheSynchronizer,
                                           ChatRunAdmissionCommitService runAdmissionCommitService) {
        this.chatRunService = chatRunService;
        this.runStartCoordinator = runStartCoordinator;
        this.cacheSynchronizer = cacheSynchronizer;
        this.runAdmissionCommitService = runAdmissionCommitService;
    }

    public Admission admit(StandardRunInputPreparer.PreparedRun prepared) {
        ChatRunAdmissionCommitService.AdmissionResult admission;
        if (prepared.directDomainAgentWaitBypass()) {
            admission = runAdmissionCommitService.commitDirectDomainAgent(
                    prepared.user(), prepared.command(), prepared.session(), prepared.runId(),
                    prepared.attachments());
        } else {
            admission = runAdmissionCommitService.commit(
                    prepared.user(), prepared.command(), prepared.session(), prepared.runId(),
                    prepared.attachments());
        }
        ChatRunMessagePlan messagePlan = admission.messagePlan();
        ChatRun run = admission.run();
        admission.cancelledBindings().forEach(cacheSynchronizer::schedule);
        RunStartAttempt startAttempt = prepared.startAttempt();
        runStartCoordinator.trackRun(startAttempt, run, "after-run-admission");
        chatRunService.synchronizeCommittedRunCache(run);
        runStartCoordinator.ensureActive(startAttempt, "after-run-cache-sync");
        return new Admission(messagePlan, run);
    }

    public record Admission(ChatRunMessagePlan messagePlan, ChatRun run) {
    }
}
