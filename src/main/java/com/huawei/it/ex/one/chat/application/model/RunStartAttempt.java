package com.huawei.it.ex.one.chat.application.model;

import com.huawei.it.ex.one.security.domain.UserContext;
import com.huawei.it.ex.one.chat.domain.ChatInteractionRequest;
import com.huawei.it.ex.one.chat.domain.ChatRun;
import com.huawei.it.ex.one.chat.domain.RunExecutionClaim;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Mutable, request-scoped control state for the first persisted event handoff. */
public final class RunStartAttempt {
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

    public RunStartAttempt(UserContext user, String runId, String interactionId) {
        this.user = user;
        this.runId = runId;
        this.interactionId = interactionId;
    }

    public boolean beginFirstEventHandoff() {
        return handoffState.compareAndSet(HandoffState.PENDING, HandoffState.HANDED_OFF);
    }

    public boolean abort() {
        return handoffState.compareAndSet(HandoffState.PENDING, HandoffState.ABORTED);
    }

    public boolean abortFailedHandoff() {
        return handoffState.compareAndSet(HandoffState.HANDED_OFF, HandoffState.ABORTED);
    }

    public boolean aborted() {
        return handoffState.get() == HandoffState.ABORTED;
    }

    public void recordRun(ChatRun value) {
        run.set(value);
    }

    public void recordExecutionClaim(RunExecutionClaim value) {
        executionClaim.set(value);
    }

    public void recordInteraction(ChatInteractionRequest value) {
        interactionRequest.set(value);
    }

    public void markExecutionInitializationSkipped() {
        executionInitializationSkipped.set(true);
    }

    public boolean beginCompensation() {
        return compensationActive.compareAndSet(false, true);
    }

    public void finishCompensation() {
        compensationActive.set(false);
    }

    public void requestCompensationRetry() {
        compensationRetryRequested.set(true);
    }

    public boolean consumeCompensationRetry() {
        return compensationRetryRequested.compareAndSet(true, false);
    }

    public UserContext user() {
        return user;
    }

    public String runId() {
        return runId;
    }

    public String interactionId() {
        return interactionId;
    }

    public ChatRun run() {
        return run.get();
    }

    public RunExecutionClaim executionClaim() {
        return executionClaim.get();
    }

    public ChatInteractionRequest interactionRequest() {
        return interactionRequest.get();
    }

    public boolean executionInitializationSkipped() {
        return executionInitializationSkipped.get();
    }

    private enum HandoffState {
        PENDING,
        HANDED_OFF,
        ABORTED
    }
}
