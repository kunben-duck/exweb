/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackNotReadyException;

import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/** Validates and publishes trusted DomainAgent background-task completion callbacks. */
@Service
public class DomainAgentAsyncTaskCallbackApplicationService {
    private static final int MAX_ERROR_CODE_POINTS = 1024;
    private static final AppLogger log =
            AppLoggerFactory.getLogger(DomainAgentAsyncTaskCallbackApplicationService.class);

    private final DomainAgentProperties properties;
    private final DomainAgentAsyncTaskCallbackCommitService commitService;
    private final ChatRunRepository runRepository;
    private final ChatRunApplicationService runService;
    private final ChatStreamApplicationService streamService;
    private final RuntimeBindingApplicationService bindingService;
    private final Scheduler callbackScheduler;

    public DomainAgentAsyncTaskCallbackApplicationService(
            DomainAgentProperties properties,
            DomainAgentAsyncTaskCallbackCommitService commitService,
            ChatRunRepository runRepository,
            ChatRunApplicationService runService,
            ChatStreamApplicationService streamService,
            RuntimeBindingApplicationService bindingService) {
        this.properties = properties;
        this.commitService = commitService;
        this.runRepository = runRepository;
        this.runService = runService;
        this.streamService = streamService;
        this.bindingService = bindingService;
        int concurrency = properties.requiredAsyncTaskCallbackMaxConcurrency();
        this.callbackScheduler = Schedulers.newBoundedElastic(
                concurrency, concurrency, "domain-agent-async-callback");
    }

    public Mono<CallbackResult> callback(DomainAgentAsyncTaskCallbackCommand command) {
        return Mono.defer(() -> {
            if (!properties.isAsyncTaskEnabled()) {
                return Mono.error(new IllegalStateException("DomainAgent async task protocol is disabled"));
            }
            return Mono.fromCallable(() -> process(command))
                    .subscribeOn(callbackScheduler);
        });
    }

    private CallbackResult process(DomainAgentAsyncTaskCallbackCommand command) {
        ValidatedCallback validated = validate(command);
        ChatRun sourceRun = runRepository.findById(validated.runId())
                .orElse(null);
        if (sourceRun == null || sourceRun.status() != ChatRunStatus.RUNNING) {
            return new CallbackResult(false);
        }
        if (!DomainAgentAsyncTaskMetadata.isAsyncRunning(sourceRun)) {
            throw new DomainAgentAsyncCallbackNotReadyException();
        }
        Instant expiresAt = DomainAgentAsyncTaskMetadata.expiresAt(sourceRun);
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            return new CallbackResult(false);
        }
        DomainAgentAsyncTaskCallbackCommitService.CommitResult committed = commitService.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        validated.runId(), validated.completed(), validated.error()));
        if (!committed.accepted()) {
            return new CallbackResult(false);
        }
        runService.synchronizeCommittedRunCache(committed.run());
        completeBindingBestEffort(committed.run(), committed.assistantMessageId());
        publishBestEffort(committed.events());
        return new CallbackResult(true);
    }

    private ValidatedCallback validate(DomainAgentAsyncTaskCallbackCommand command) {
        if (command == null || command.runId() == null || command.runId().length() > 64) {
            throw new IllegalArgumentException("runId不能为空且长度不能超过64");
        }
        String status = command.status() == null ? "" : command.status().toUpperCase(Locale.ROOT);
        if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
            throw new IllegalArgumentException("status仅支持COMPLETED或FAILED");
        }
        boolean completed = "COMPLETED".equals(status);
        String error = completed ? null : truncateError(command.error());
        return new ValidatedCallback(command.runId(), completed, error);
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        int codePoints = error.codePointCount(0, error.length());
        if (codePoints <= MAX_ERROR_CODE_POINTS) {
            return error;
        }
        int endIndex = error.offsetByCodePoints(0, MAX_ERROR_CODE_POINTS);
        return error.substring(0, endIndex);
    }

    private void publishBestEffort(List<DomainAgentAsyncTaskCallbackCommitService.PublishedEvent> events) {
        for (DomainAgentAsyncTaskCallbackCommitService.PublishedEvent item : events) {
            try {
                if (item.persisted()) {
                    streamService.publishPersisted(item.event());
                } else {
                    streamService.publishLiveOnly(item.event());
                }
            } catch (RuntimeException ex) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.WEBSOCKET_SEND_FAILED,
                                "DomainAgent async callback was committed but realtime publication failed")
                        .runId(item.event().runId())
                        .sessionId(item.event().sessionId())
                        .operation("domain-agent.async-callback.publish")
                        .build(), ex);
            }
        }
    }

    private void completeBindingBestEffort(
            ChatRun run,
            String assistantMessageId) {
        try {
            bindingService.completeDomainAgentAfterAsyncRun(
                    run.tenantId(), run.userId(), run.sessionId(), run.id(), assistantMessageId);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.DATABASE_WRITE_FAILED,
                            "DomainAgent async callback binding completion failed")
                    .runId(run.id())
                    .sessionId(run.sessionId())
                    .operation("domain-agent.async-callback.binding")
                    .build(), ex);
        }
    }

    @PreDestroy
    void closeScheduler() {
        callbackScheduler.dispose();
    }

    public record CallbackResult(boolean accepted) {
    }

    private record ValidatedCallback(
            String runId,
            boolean completed,
            String error) {
    }
}
