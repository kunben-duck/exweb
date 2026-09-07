/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.integration.intent.IntentFeedbackException;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.intent.IntentFeedback;

import reactor.core.publisher.Mono;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** Uses dedicated feedback admission without occupying preference or chat execution workers. */
@Service
public class IntentFeedbackApplicationService {
    private final IntentFeedbackCommitService commits;
    private final IntentFeedbackTaskDispatcher dispatcher;

    public IntentFeedbackApplicationService(IntentFeedbackCommitService commits,
            IntentFeedbackTaskDispatcher dispatcher) {
        this.commits = commits;
        this.dispatcher = dispatcher;
    }

    public Mono<IntentFeedback> record(UserContext user, String runId, IntentFeedbackCommand command) {
        return dispatcher.submit(() -> {
            try {
                return commits.record(user, runId, command);
            } catch (DuplicateKeyException duplicate) {
                // The failed transaction has rolled back; read/compare the winner in a fresh transaction.
                return commits.record(user, runId, command);
            }
        }).onErrorMap(this::infrastructureFailure, IntentFeedbackException::unavailable);
    }

    public Mono<Optional<IntentFeedback>> find(UserContext user, String runId) {
        return dispatcher.submit(() -> commits.find(user, runId))
                .onErrorMap(this::infrastructureFailure, IntentFeedbackException::unavailable);
    }

    private boolean infrastructureFailure(Throwable error) {
        return !(error instanceof IllegalArgumentException)
                && !(error instanceof SecurityException) && !(error instanceof IntentFeedbackException);
    }
}
