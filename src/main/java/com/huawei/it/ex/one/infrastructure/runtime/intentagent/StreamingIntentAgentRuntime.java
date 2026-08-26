package com.huawei.it.ex.one.infrastructure.runtime.intentagent;

import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteFrame;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteResult;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamClient;
import com.huawei.it.ex.one.application.integration.intent.IntentDecisionStreamFrame;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.domain.chat.RuntimeEvent;

import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Streaming IntentAgent runtime.
 *
 * <p>Process events are converted to stable ChatEvent types while the final {@code result} frame is
 * kept on the existing intent routing path.</p>
 */
public class StreamingIntentAgentRuntime implements IntentAgentRuntime {
    private final IntentDecisionStreamClient streamClient;

    public StreamingIntentAgentRuntime(IntentDecisionStreamClient streamClient) {
        this.streamClient = streamClient;
    }

    @Override
    public Flux<IntentAgentRouteFrame> route(IntentAgentRouteRequest request) {
        long started = System.nanoTime();
        Flux<IntentAgentRouteFrame> start = startFrame(request);
        Flux<IntentAgentRouteFrame> stream = streamClient
                .recognize(request.command(), request.memory(), request.user(), request.userMessageId())
                .concatMap(frame -> toRouteFrames(request, frame, started));
        return start.concatWith(stream);
    }

    private Flux<IntentAgentRouteFrame> startFrame(IntentAgentRouteRequest request) {
        if (!hasEventIdentity(request)) {
            return Flux.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", PROVIDER);
        payload.put("sourceType", "intent-start");
        payload.put("stage", "intent_calling");
        payload.put("message", "正在识别问题意图");
        payload.put("routeTrigger", request.routeTrigger() == null ? "" : request.routeTrigger());
        return Flux.just(IntentAgentRouteFrame.event(
                RuntimeEvent.progress(request.runId(), request.session().id(), Map.copyOf(payload))));
    }

    private Flux<IntentAgentRouteFrame> toRouteFrames(IntentAgentRouteRequest request, IntentDecisionStreamFrame frame,
                                                      long started) {
        if (frame.type() != IntentDecisionStreamFrame.Type.RESULT && !hasEventIdentity(request)) {
            return Flux.empty();
        }
        return switch (frame.type()) {
            case PROGRESS -> Flux.just(IntentAgentRouteFrame.event(progressEvent(request, frame)));
            case DELTA -> Flux.just(IntentAgentRouteFrame.event(deltaEvent(request, frame)));
            case RESULT -> Flux.just(IntentAgentRouteFrame.result(new IntentAgentRouteResult(
                    safeResult(frame.recognitionResult()),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            )));
        };
    }

    private RuntimeEvent progressEvent(IntentAgentRouteRequest request, IntentDecisionStreamFrame frame) {
        Map<String, Object> payload = baseProcessPayload("intent-progress", frame);
        copyText(frame.payload(), payload, "stage", "stage");
        copyText(frame.payload(), payload, "stageMessage", "message");
        return RuntimeEvent.progress(request.runId(), request.session().id(), Map.copyOf(payload));
    }

    private RuntimeEvent deltaEvent(IntentAgentRouteRequest request, IntentDecisionStreamFrame frame) {
        Map<String, Object> payload = baseProcessPayload("intent-delta", frame);
        payload.put("stage", "LLM_PROCESSING");
        Object index = frame.payload().get("index");
        if (index != null) {
            payload.put("index", index);
        }
        copyText(frame.payload(), payload, "content", "text");
        return RuntimeEvent.thinking(request.runId(), request.session().id(), Map.copyOf(payload));
    }

    private Map<String, Object> baseProcessPayload(String sourceType, IntentDecisionStreamFrame frame) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", PROVIDER);
        payload.put("sourceType", sourceType);
        payload.put("attempt", frame.attempt());
        payload.put("maxAttempts", frame.maxAttempts());
        return payload;
    }

    private void copyText(Map<String, Object> source, Map<String, Object> target,
                          String sourceKey, String targetKey) {
        Object value = source.get(sourceKey);
        if (value != null && !String.valueOf(value).isBlank()) {
            target.put(targetKey, String.valueOf(value));
        }
    }

    private IntentRecognitionResult safeResult(IntentRecognitionResult result) {
        if (result == null) {
            throw new IllegalStateException("IntentDecision stream returned an empty final result");
        }
        return result;
    }

    private boolean hasEventIdentity(IntentAgentRouteRequest request) {
        return request != null
                && request.runId() != null
                && !request.runId().isBlank()
                && request.session() != null
                && request.session().id() != null
                && !request.session().id().isBlank();
    }
}
