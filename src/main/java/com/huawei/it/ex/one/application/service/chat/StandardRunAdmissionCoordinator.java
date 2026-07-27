package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;

/** Preserves standard run admission selection and post-commit cache order. */
final class StandardRunAdmissionCoordinator {
    private final ChatRunApplicationService chatRunService;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final RuntimeBindingCacheSynchronizer cacheSynchronizer;
    private final ChatRunAdmissionCoordinator admissionCoordinator;

    StandardRunAdmissionCoordinator(
            ChatRunApplicationService chatRunService,
            ChatRunStartCoordinator runStartCoordinator,
            RuntimeBindingCacheSynchronizer cacheSynchronizer,
            ChatRunAdmissionCoordinator admissionCoordinator) {
        this.chatRunService = chatRunService;
        this.runStartCoordinator = runStartCoordinator;
        this.cacheSynchronizer = cacheSynchronizer;
        this.admissionCoordinator = admissionCoordinator;
    }

    Admission admit(StandardRunInputPreparer.PreparedRun prepared) {
        ChatRunAdmissionCommitService.AdmissionResult result =
                admissionCoordinator.admitStandard(
                        new ChatRunAdmissionCoordinator.StandardAdmission(
                                prepared.user(),
                                prepared.command(),
                                prepared.session(),
                                prepared.runId(),
                                prepared.attachments(),
                                prepared.directDomainAgentWaitBypass()));
        ChatRunMessagePlan messagePlan = result.messagePlan();
        ChatRun run = result.run();
        result.cancelledBindings().forEach(cacheSynchronizer::schedule);
        runStartCoordinator.trackRun(
                prepared.startAttempt(), run, "after-run-admission");
        chatRunService.synchronizeCommittedRunCache(run);
        runStartCoordinator.ensureActive(
                prepared.startAttempt(), "after-run-cache-sync");
        return new Admission(messagePlan, run);
    }

    record Admission(ChatRunMessagePlan messagePlan, ChatRun run) {
    }
}
