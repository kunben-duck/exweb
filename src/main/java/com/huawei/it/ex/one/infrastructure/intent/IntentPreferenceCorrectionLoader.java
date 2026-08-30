/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.config.RouteMemoryProperties;
import com.huawei.it.ex.one.application.integration.intent.IntentAccessNameResolver;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceCorrectionRepository;
import com.huawei.it.ex.one.application.integration.intent.IntentUserPreferenceCorrection;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Loads recent preferences once per logical Intent call with an independent fail-open circuit. */
@Component
public class IntentPreferenceCorrectionLoader {
    private static final AppLogger log = AppLoggerFactory.getLogger(IntentPreferenceCorrectionLoader.class);

    private final IntentPreferenceCorrectionRepository repository;
    private final IntentAccessNameResolver accessNameResolver;
    private final IntentServiceHttpProperties intentProperties;
    private final RouteMemoryProperties isolationProperties;
    private final Scheduler readScheduler;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicReference<Instant> circuitOpenUntil = new AtomicReference<>(Instant.EPOCH);

    public IntentPreferenceCorrectionLoader(
            IntentPreferenceCorrectionRepository repository,
            IntentAccessNameResolver accessNameResolver,
            IntentServiceHttpProperties intentProperties,
            RouteMemoryProperties isolationProperties,
            @Qualifier("intentPreferenceReadExecutor") Executor readExecutor) {
        this.repository = repository;
        this.accessNameResolver = accessNameResolver;
        this.intentProperties = intentProperties;
        this.isolationProperties = isolationProperties;
        this.readScheduler = Schedulers.fromExecutor(readExecutor);
    }

    public Mono<List<IntentUserPreferenceCorrection>> load(ChatCommand command, UserContext user) {
        int limit = intentProperties.getUserPreferenceCorrectionsLimit();
        if (limit == 0 || user == null || circuitOpen()) {
            return Mono.just(List.of());
        }
        String accessName = accessNameResolver.resolve(command == null ? null : command.intentAccessName());
        if (accessName == null || accessName.isBlank()) {
            return Mono.just(List.of());
        }
        return Mono.fromCallable(() -> repository.findRecent(
                        user.tenantId(), user.ownerUserId(), accessName, limit))
                .subscribeOn(readScheduler)
                .timeout(isolationProperties.normalizedReadTimeout())
                .map(items -> items == null ? List.<IntentUserPreferenceCorrection>of() : List.copyOf(items))
                .doOnSuccess(ignored -> recordSuccess())
                .onErrorResume(failure -> {
                    recordFailure(failure);
                    return Mono.just(List.of());
                });
    }

    public List<IntentUserPreferenceCorrection> loadBlocking(ChatCommand command, UserContext user) {
        return load(command, user).blockOptional().orElseGet(List::of);
    }

    private boolean circuitOpen() {
        return Instant.now().isBefore(circuitOpenUntil.get());
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
        circuitOpenUntil.set(Instant.EPOCH);
    }

    private void recordFailure(Throwable failure) {
        int failures = consecutiveFailures.incrementAndGet();
        boolean opened = failures >= isolationProperties.normalizedCircuitBreakerFailureThreshold();
        if (opened) {
            circuitOpenUntil.set(Instant.now().plus(
                    isolationProperties.normalizedCircuitBreakerOpenDuration()));
            consecutiveFailures.set(0);
        }
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_READ_FAILED,
                        "Intent preference read failed and was degraded to an empty list")
                .operation("intent-preference.read")
                .attribute("circuitOpened", opened)
                .attribute("failureType", failure.getClass().getName())
                .build(), failure);
    }
}
