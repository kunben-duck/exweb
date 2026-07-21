package com.huawei.it.ex.one.chat.application.service;

import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.ChatSession;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.security.domain.UserContext;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.scheduler.Schedulers;

/** Preserves the existing active-run stop timing around session deletion. */
@Service
public final class SessionDeleteRunSupport {
    private static final AppLogger log =
            com.huawei.it.ex.one.common.logging.AppLoggerFactory.getLogger(SessionApplicationService.class);

    private final ChatRunApplicationService chatRunService;
    private final RuntimeBindingService runtimeBindingService;
    private final ChatSessionLifecycleService shareLifecycleService;
    private final ObjectProvider<ChatRunStopCoordinator> stopCoordinatorProvider;
    private final ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider;

    @Autowired
    public SessionDeleteRunSupport(
            ChatRunApplicationService chatRunService,
            RuntimeBindingService runtimeBindingService,
            ChatSessionLifecycleService shareLifecycleService,
            ObjectProvider<ChatRunStopCoordinator> stopCoordinatorProvider,
            ObjectProvider<ChatInteractionApplicationService> interactionServiceProvider) {
        this.chatRunService = chatRunService;
        this.runtimeBindingService = runtimeBindingService;
        this.shareLifecycleService = shareLifecycleService;
        this.stopCoordinatorProvider = stopCoordinatorProvider;
        this.interactionServiceProvider = interactionServiceProvider;
    }

    void afterSessionDeleted(UserContext user, ChatSession session) {
        if (runtimeBindingService != null) {
            runtimeBindingService.cancelAllForSession(user.tenantId(), user.ownerUserId(), session.id());
        }
        if (shareLifecycleService != null) {
            shareLifecycleService.revokeActiveBySession(
                    user.tenantId(), user.ownerUserId(), session.id(), Instant.now());
        }
        ChatInteractionApplicationService interactionService = interactionServiceProvider == null
                ? null
                : interactionServiceProvider.getIfAvailable();
        if (interactionService != null) {
            interactionService.cancelOpenBySession(user, session.id());
        }
    }

    List<DeleteRunPlan> activeRunPlans(UserContext user, List<ChatSession> sessions) {
        if (sessions == null || sessions.isEmpty() || stopCoordinatorProvider == null) {
            return List.of();
        }
        if (stopCoordinatorProvider.getIfAvailable() == null || chatRunService == null) {
            return List.of();
        }
        List<DeleteRunPlan> plans = new ArrayList<>();
        for (ChatSession session : sessions) {
            chatRunService.findActiveRun(user, session.id())
                    .ifPresent(run -> plans.add(new DeleteRunPlan(session, run)));
        }
        return List.copyOf(plans);
    }

    void stopAfterCommit(UserContext user, List<DeleteRunPlan> plans) {
        if (plans == null || plans.isEmpty() || stopCoordinatorProvider == null) {
            return;
        }
        ChatRunStopCoordinator stopCoordinator = stopCoordinatorProvider.getIfAvailable();
        if (stopCoordinator == null) {
            return;
        }
        Runnable stopTask = () -> plans.forEach(plan -> stopRun(user, plan, stopCoordinator));
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Schedulers.boundedElastic().schedule(stopTask);
                }
            });
        } else {
            stopTask.run();
        }
    }

    private void stopRun(UserContext user, DeleteRunPlan plan, ChatRunStopCoordinator stopCoordinator) {
        try {
            stopCoordinator.stopRunForSessionDelete(user, plan.run(), plan.session());
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTERNAL_EXECUTION_FAILED,
                            "Active run stop failed after session deletion committed")
                    .runId(plan.run().id())
                    .sessionId(plan.session().id())
                    .operation("chat-session.delete.stop-run")
                    .build(), ex);
        }
    }

    record DeleteRunPlan(ChatSession session, ChatRun run) {
    }
}
