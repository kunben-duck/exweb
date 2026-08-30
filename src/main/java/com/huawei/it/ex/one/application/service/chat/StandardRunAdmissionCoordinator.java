/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService.AdmissionCancellation;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunMessagePlan;

import java.util.List;

/** Preserves standard run admission selection and post-commit cache order. */
final class StandardRunAdmissionCoordinator {
    private final ChatRunApplicationService chatRunService;
    private final ChatRunStartCoordinator runStartCoordinator;
    private final RuntimeBindingCacheSynchronizer cacheSynchronizer;
    private final ChatRunAdmissionCoordinator admissionCoordinator;
    private final SessionTitleApplicationService sessionTitleService;

    StandardRunAdmissionCoordinator(
            ChatRunApplicationService chatRunService,
            ChatRunStartCoordinator runStartCoordinator,
            RuntimeBindingCacheSynchronizer cacheSynchronizer,
            ChatRunAdmissionCoordinator admissionCoordinator,
            SessionTitleApplicationService sessionTitleService) {
        this.chatRunService = chatRunService;
        this.runStartCoordinator = runStartCoordinator;
        this.cacheSynchronizer = cacheSynchronizer;
        this.admissionCoordinator = admissionCoordinator;
        this.sessionTitleService = sessionTitleService;
    }

    StandardRunAdmissionCoordinator(
            ChatRunApplicationService chatRunService,
            ChatRunStartCoordinator runStartCoordinator,
            RuntimeBindingCacheSynchronizer cacheSynchronizer,
            ChatRunAdmissionCoordinator admissionCoordinator) {
        this(chatRunService, runStartCoordinator, cacheSynchronizer, admissionCoordinator, null);
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
                                prepared.explicitRuntimeTarget(),
                                prepared.directRuntimeWaitBypass()));
        return completeAdmission(prepared, result);
    }

    Admission admitCandidateSwitch(
            StandardRunInputPreparer.PreparedRun prepared,
            CandidateSwitchRunSource source) {
        ChatRunAdmissionCommitService.AdmissionResult result =
                admissionCoordinator.admitCandidateSwitch(
                        new ChatRunAdmissionCoordinator.CandidateSwitchAdmission(
                                prepared.user(),
                                prepared.command(),
                                prepared.runId(),
                                source));
        return completeAdmission(prepared, result);
    }

    private Admission completeAdmission(
            StandardRunInputPreparer.PreparedRun prepared,
            ChatRunAdmissionCommitService.AdmissionResult result) {
        ChatRunMessagePlan messagePlan = result.messagePlan();
        ChatRun run = result.run();
        result.cancelledBindings().forEach(cacheSynchronizer::schedule);
        runStartCoordinator.trackRun(
                prepared.startAttempt(), run, "after-run-admission");
        chatRunService.synchronizeCommittedRunCache(run);
        runStartCoordinator.ensureActive(
                prepared.startAttempt(), "after-run-cache-sync");
        if (sessionTitleService != null) {
            sessionTitleService.schedule(
                    prepared.user(), prepared.command(), prepared.session(), messagePlan, run);
        }
        return new Admission(messagePlan, run, result.bindingCancellations());
    }

    record Admission(
            ChatRunMessagePlan messagePlan,
            ChatRun run,
            List<AdmissionCancellation> bindingCancellations) {
        Admission {
            bindingCancellations = bindingCancellations == null
                    ? List.of()
                    : List.copyOf(bindingCancellations);
        }

        Admission(ChatRunMessagePlan messagePlan, ChatRun run) {
            this(messagePlan, run, List.of());
        }
    }
}
