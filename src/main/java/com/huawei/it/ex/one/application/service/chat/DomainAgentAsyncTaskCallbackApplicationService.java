package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;
import com.huawei.it.ex.one.application.integration.conversation.ChatRunRepository;
import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackBusyException;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;

/** Validates, normalizes and publishes trusted DomainAgent background-task callbacks. */
@Service
public class DomainAgentAsyncTaskCallbackApplicationService {
    private static final AppLogger log =
            AppLoggerFactory.getLogger(DomainAgentAsyncTaskCallbackApplicationService.class);

    private final DomainAgentProperties properties;
    private final DomainAgentResponseNormalizer normalizer;
    private final DomainAgentAsyncTaskCallbackCommitService commitService;
    private final ChatRunRepository runRepository;
    private final ChatRunApplicationService runService;
    private final ChatStreamApplicationService streamService;
    private final RuntimeBindingApplicationService bindingService;
    private final ObjectMapper objectMapper;
    private final Semaphore permits;
    private final Scheduler callbackScheduler;

    public DomainAgentAsyncTaskCallbackApplicationService(
            DomainAgentProperties properties,
            DomainAgentResponseNormalizer normalizer,
            DomainAgentAsyncTaskCallbackCommitService commitService,
            ChatRunRepository runRepository,
            ChatRunApplicationService runService,
            ChatStreamApplicationService streamService,
            RuntimeBindingApplicationService bindingService,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.normalizer = normalizer;
        this.commitService = commitService;
        this.runRepository = runRepository;
        this.runService = runService;
        this.streamService = streamService;
        this.bindingService = bindingService;
        this.objectMapper = objectMapper;
        int concurrency = properties.requiredAsyncTaskCallbackMaxConcurrency();
        this.permits = new Semaphore(concurrency, true);
        this.callbackScheduler = Schedulers.newBoundedElastic(
                concurrency, concurrency, "domain-agent-async-callback");
    }

    public Mono<CallbackResult> callback(DomainAgentAsyncTaskCallbackCommand command) {
        return Mono.defer(() -> {
            if (!properties.isAsyncTaskEnabled()) {
                return Mono.error(new IllegalStateException("DomainAgent async task protocol is disabled"));
            }
            if (!permits.tryAcquire()) {
                return Mono.error(new DomainAgentAsyncCallbackBusyException());
            }
            return Mono.fromCallable(() -> process(command))
                    .subscribeOn(callbackScheduler)
                    .doFinally(ignored -> permits.release());
        });
    }

    private CallbackResult process(DomainAgentAsyncTaskCallbackCommand command) {
        ValidatedCallback validated = validate(command);
        com.huawei.it.ex.one.domain.chat.ChatRun sourceRun = runRepository.findById(validated.runId())
                .orElse(null);
        if (!DomainAgentAsyncTaskMetadata.isAsyncRunning(sourceRun)
                || sourceRun.status() != com.huawei.it.ex.one.domain.chat.ChatRunStatus.RUNNING) {
            return new CallbackResult(false);
        }
        List<ChatEvent> businessEvents = normalize(
                validated.runId(), sourceRun.sessionId(), validated.frames());
        DomainAgentAsyncTaskCallbackCommitService.CommitResult committed = commitService.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        validated.runId(), validated.completed(), validated.resultMode(),
                        businessEvents, validated.error()));
        if (!committed.accepted()) {
            return new CallbackResult(false);
        }
        runService.synchronizeCommittedRunCache(committed.run());
        publishBestEffort(committed.events());
        completeBindingBestEffort(committed.run(), committed.assistantMessageId());
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
        List<JsonNode> frames = command.frames();
        if (frames.size() > properties.requiredAsyncTaskCallbackMaxFrames()) {
            throw new IllegalArgumentException("DomainAgent异步回调frames数量超过限制");
        }
        for (JsonNode frame : frames) {
            if (frame == null || !frame.isObject()) {
                throw new IllegalArgumentException("DomainAgent异步回调frame必须是JSON object");
            }
            if ("agent.async_started".equals(frame.path("type").asText(null))) {
                throw new IllegalArgumentException("DomainAgent异步回调不能再次启动异步任务");
            }
        }
        int bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(frames).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("DomainAgent异步回调frames无法序列化", ex);
        }
        if (bytes > properties.requiredAsyncTaskCallbackMaxBytes()) {
            throw new IllegalArgumentException("DomainAgent异步回调frames字节数超过限制");
        }
        String resultMode = command.resultMode() == null
                ? null : command.resultMode().toUpperCase(Locale.ROOT);
        if (!frames.isEmpty() && !"APPEND".equals(resultMode) && !"REPLACE".equals(resultMode)) {
            throw new IllegalArgumentException("frames非空时resultMode仅支持APPEND或REPLACE");
        }
        return new ValidatedCallback(
                command.runId(), "COMPLETED".equals(status), resultMode,
                frames, command.error());
    }

    private List<ChatEvent> normalize(String runId, String sessionId, List<JsonNode> frames) {
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();
        List<ChatEvent> normalized = new ArrayList<>();
        for (JsonNode frame : frames) {
            for (ChatEvent event : normalizer.normalizeCallbackFrame(runId, sessionId, frame, state)) {
                if (!"message.completed".equals(event.type())) {
                    normalized.add(event);
                }
            }
        }
        for (ChatEvent event : normalizer.finish(runId, sessionId, state)) {
            if (!"message.completed".equals(event.type())) {
                normalized.add(event);
            }
        }
        return List.copyOf(normalized);
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
            com.huawei.it.ex.one.domain.chat.ChatRun run,
            String assistantMessageId) {
        try {
            RuntimeBinding binding = bindingService.findActiveDomainAgentBySession(
                            run.tenantId(), run.userId(), run.sessionId())
                    .filter(candidate -> run.id().equals(candidate.lastRunId()))
                    .orElse(null);
            bindingService.completeAfterRun(binding, run.id(), assistantMessageId);
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
            String resultMode,
            List<JsonNode> frames,
            JsonNode error) {
    }
}
