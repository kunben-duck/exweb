package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatInteractionRequest;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.RunExecutionClaim;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable request-scoped state for first persisted-event handoff and compensation. */
final class RunStartAttempt {
    private final UserContext user;
    private final String runId;
    private final String interactionId;
    private final AtomicReference<HandoffState> handoffState = new AtomicReference<>(HandoffState.PENDING);
    private final AtomicReference<ChatRun> run = new AtomicReference<>();
    private final AtomicReference<RunExecutionClaim> executionClaim = new AtomicReference<>();
    private final AtomicReference<ChatInteractionRequest> interactionRequest = new AtomicReference<>();
    private final AtomicBoolean executionInitializationSkipped = new AtomicBoolean(false);
    private final AtomicBoolean compensationActive = new AtomicBoolean(false);
    private final AtomicBoolean compensationRetryRequested = new AtomicBoolean(false);

    RunStartAttempt(UserContext user, String runId, String interactionId) {
        this.user = user;
        this.runId = runId;
        this.interactionId = interactionId;
    }

    boolean beginFirstEventHandoff() {
        return handoffState.compareAndSet(HandoffState.PENDING, HandoffState.HANDED_OFF);
    }

    boolean abort() {
        return handoffState.compareAndSet(HandoffState.PENDING, HandoffState.ABORTED);
    }

    boolean abortFailedHandoff() {
        return handoffState.compareAndSet(HandoffState.HANDED_OFF, HandoffState.ABORTED);
    }

    boolean aborted() {
        return handoffState.get() == HandoffState.ABORTED;
    }

    void recordRun(ChatRun value) {
        run.set(value);
    }

    void recordExecutionClaim(RunExecutionClaim value) {
        executionClaim.set(value);
    }

    void recordInteraction(ChatInteractionRequest value) {
        interactionRequest.set(value);
    }

    void markExecutionInitializationSkipped() {
        executionInitializationSkipped.set(true);
    }

    boolean beginCompensation() {
        return compensationActive.compareAndSet(false, true);
    }

    void finishCompensation() {
        compensationActive.set(false);
    }

    void requestCompensationRetry() {
        compensationRetryRequested.set(true);
    }

    boolean consumeCompensationRetry() {
        return compensationRetryRequested.compareAndSet(true, false);
    }

    UserContext user() {
        return user;
    }

    String runId() {
        return runId;
    }

    String interactionId() {
        return interactionId;
    }

    ChatRun run() {
        return run.get();
    }

    RunExecutionClaim executionClaim() {
        return executionClaim.get();
    }

    ChatInteractionRequest interactionRequest() {
        return interactionRequest.get();
    }

    boolean executionInitializationSkipped() {
        return executionInitializationSkipped.get();
    }

    private enum HandoffState {
        PENDING,
        HANDED_OFF,
        ABORTED
    }
}
