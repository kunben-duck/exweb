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
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRun;
import com.huawei.it.ex.one.domain.chat.ChatRunStatus;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackNotReadyException;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackPayloadTooLargeException;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentControlEventMapper;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentProtocolException;
import com.huawei.it.ex.one.infrastructure.runtime.domainagent.DomainAgentResponseNormalizer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates and publishes trusted DomainAgent background-task completion callbacks. */
@Service
public class DomainAgentAsyncTaskCallbackApplicationService {
    private static final int MAX_ERROR_CODE_POINTS = 1024;
    private static final Set<String> TERMINAL_FRAME_TYPES = Set.of(
            "message.completed", "run.completed", "run.failed", "run.cancelled", "agent.async_finished");
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
        List<ChatEvent> businessEvents = normalize(
                validated.runId(), sourceRun.sessionId(), validated.frames());
        boolean resultProvided = !businessEvents.isEmpty();
        if (resultProvided && validated.resultMode() == null) {
            throw new IllegalArgumentException("异步回调包含业务结果时resultMode仅支持APPEND或REPLACE");
        }
        DomainAgentAsyncTaskCallbackCommitService.CommitResult committed = commitService.commit(
                new DomainAgentAsyncTaskCallbackCommitService.PreparedCallback(
                        validated.runId(), validated.completed(), validated.resultMode(),
                        resultProvided, businessEvents, validated.error()));
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
        List<JsonNode> frames = command.frames();
        if (frames.size() > properties.requiredAsyncTaskCallbackMaxFrames()) {
            throw payloadTooLarge("DomainAgent异步回调frames数量超过限制");
        }
        for (JsonNode frame : frames) {
            validateFrame(frame);
        }
        String resultMode = command.resultMode() == null
                ? null : command.resultMode().toUpperCase(Locale.ROOT);
        if (resultMode != null && !"APPEND".equals(resultMode) && !"REPLACE".equals(resultMode)) {
            throw new IllegalArgumentException("resultMode仅支持APPEND或REPLACE");
        }
        String error = completed ? null : truncateError(command.error());
        return new ValidatedCallback(command.runId(), completed, resultMode, frames, error);
    }

    private void validateFrame(JsonNode frame) {
        if (frame == null || !frame.isObject()) {
            throw new IllegalArgumentException("DomainAgent异步回调frame必须是JSON object");
        }
        String type = frame.path("type").asText(null);
        if ("agent.async_started".equals(type)) {
            throw new IllegalArgumentException("DomainAgent异步回调不能再次启动异步任务");
        }
        if (DomainAgentControlEventMapper.REFUSAL_TYPE.equals(type)) {
            throw new IllegalArgumentException("DomainAgent异步回调不支持状态机控制事件");
        }
        try {
            int bytes = objectMapper.writeValueAsBytes(frame).length;
            if (bytes > properties.normalizedMaxPendingFrameBytes()) {
                throw payloadTooLarge("DomainAgent异步回调单帧字节数超过限制");
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("DomainAgent异步回调frame无法序列化", ex);
        }
    }

    private List<ChatEvent> normalize(String runId, String sessionId, List<JsonNode> frames) {
        if (frames.isEmpty()) {
            return List.of();
        }
        int maxEvents = properties.requiredAsyncTaskCallbackMaxEvents();
        long maxEventBytes = properties.requiredAsyncTaskCallbackMaxEventBytes();
        DomainAgentResponseNormalizer.DomainAgentStreamState state = normalizer.newStreamState();
        List<ChatEvent> events = new ArrayList<>(Math.min(frames.size(), maxEvents));
        long eventBytes = 0L;
        try {
            for (JsonNode original : frames) {
                JsonNode frame = withoutTerminalSignal(original);
                if (frame == null) {
                    continue;
                }
                preflightThinkExpansion(frame, maxEvents - events.size());
                for (ChatEvent event : normalizer.normalizeCallbackFrame(runId, sessionId, frame, state)) {
                    if (terminalEvent(event)) {
                        continue;
                    }
                    rejectControlEvent(event);
                    eventBytes = addNormalizedEvent(events, event, eventBytes, maxEvents, maxEventBytes);
                }
            }
            for (ChatEvent event : normalizer.finish(runId, sessionId, state)) {
                if (!terminalEvent(event)) {
                    rejectControlEvent(event);
                    eventBytes = addNormalizedEvent(events, event, eventBytes, maxEvents, maxEventBytes);
                }
            }
        } catch (DomainAgentProtocolException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
        return List.copyOf(events);
    }

    private void rejectControlEvent(ChatEvent event) {
        if (event != null
                && DomainAgentControlEventMapper.fromNormalizedPayload(event.payload()).isPresent()) {
            throw new IllegalArgumentException("DomainAgent异步回调不支持状态机控制事件");
        }
    }

    private JsonNode withoutTerminalSignal(JsonNode frame) {
        String type = frame.path("type").asText(null);
        if (type == null || !TERMINAL_FRAME_TYPES.contains(type)) {
            return frame;
        }
        ObjectNode businessFrame = ((ObjectNode) frame).deepCopy();
        businessFrame.remove(List.of(
                "type", "status", "message", "error", "endFlag", "finishReason"));
        return businessFrame.isEmpty() ? null : businessFrame;
    }

    private void preflightThinkExpansion(JsonNode frame, int remainingEvents) {
        JsonNode contentNode = frame.get("content");
        if (contentNode == null || contentNode.isNull()) {
            return;
        }
        String content = contentNode.asText("");
        int boundaries = countIgnoreCase(content, "<think>")
                + countIgnoreCase(content, "</think>");
        if (boundaries > 0 && (long) boundaries * 2L + 3L > remainingEvents) {
            throw payloadTooLarge("DomainAgent异步回调标准事件数量超过限制");
        }
    }

    private int countIgnoreCase(String value, String token) {
        int count = 0;
        for (int index = 0; index <= value.length() - token.length();) {
            if (value.regionMatches(true, index, token, 0, token.length())) {
                count++;
                index += token.length();
            } else {
                index++;
            }
        }
        return count;
    }

    private long addNormalizedEvent(
            List<ChatEvent> events,
            ChatEvent event,
            long currentBytes,
            int maxEvents,
            long maxEventBytes) {
        if (events.size() >= maxEvents) {
            throw payloadTooLarge("DomainAgent异步回调标准事件数量超过限制");
        }
        long nextBytes = saturatedAdd(currentBytes, serializedEventBytes(event));
        if (nextBytes > maxEventBytes) {
            throw payloadTooLarge("DomainAgent异步回调标准事件字节数超过限制");
        }
        events.add(event);
        return nextBytes;
    }

    private long serializedEventBytes(ChatEvent event) {
        Map<String, Object> wireEvent = new LinkedHashMap<>();
        wireEvent.put("runId", event.runId());
        wireEvent.put("sessionId", event.sessionId());
        wireEvent.put("sequence", Long.MAX_VALUE);
        wireEvent.put("type", event.type());
        wireEvent.put("createdAt", event.createdAt() == null ? null : event.createdAt().toString());
        wireEvent.put("payload", event.payload() == null ? Map.of() : event.payload());
        try {
            return objectMapper.writeValueAsBytes(wireEvent).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("DomainAgent异步回调事件无法序列化", ex);
        }
    }

    private long saturatedAdd(long left, long right) {
        return right > 0 && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private boolean terminalEvent(ChatEvent event) {
        return event != null && ("message.completed".equals(event.type())
                || "run.completed".equals(event.type())
                || "run.failed".equals(event.type())
                || "run.cancelled".equals(event.type()));
    }

    private DomainAgentAsyncCallbackPayloadTooLargeException payloadTooLarge(String message) {
        return new DomainAgentAsyncCallbackPayloadTooLargeException(message);
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
            String resultMode,
            List<JsonNode> frames,
            String error) {
    }
}
