package com.huawei.it.ex.one.intent.infrastructure.runtime;

import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteFrame;
import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteRequest;
import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteResult;
import com.huawei.it.ex.one.intent.application.client.IntentAgentRuntime;
import com.huawei.it.ex.one.intent.application.model.IntentRecognitionResult;
import com.huawei.it.ex.one.intent.application.client.IntentService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.event.RuntimeEvent;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.TaskComplexity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 当前阻塞式 HTTP 意图服务的 IntentAgent 包装。
 *
 * <p>这里把同步意图调用转换为 agent-like 事件流；后续意图服务改成流式协议时，
 * 只需要替换本 adapter，ChatService 主编排仍消费同一组 IntentAgent 帧。</p>
 */
@Component
@ConditionalOnProperty(prefix = "financeex.intent", name = "enabled", havingValue = "true")
public class BlockingIntentAgentRuntime implements IntentAgentRuntime {
    private static final AppLogger log = AppLoggerFactory.getLogger(BlockingIntentAgentRuntime.class);

    private final IntentService intentService;

    public BlockingIntentAgentRuntime(IntentService intentService) {
        this.intentService = intentService;
    }

    @Override
    public Flux<IntentAgentRouteFrame> route(IntentAgentRouteRequest request) {
        Flux<IntentAgentRouteFrame> start = startFrame(request)
                .map(IntentAgentRouteFrame::event)
                .map(Flux::just)
                .orElseGet(Flux::empty);
        return start.concatWith(Mono.fromCallable(() -> recognize(request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(IntentAgentRouteFrame::result));
    }

    private IntentAgentRouteResult recognize(IntentAgentRouteRequest request) {
        long started = System.nanoTime();
        IntentRecognitionResult result;
        try {
            result = intentService.recognizeForRouting(request.command(), request.memory(), request.user());
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.INTENT_DECISION_STREAM_FAILED,
                            "IntentDecision routing failed; returning the configured degraded result")
                    .sessionId(request.session() == null ? null : request.session().id())
                    .operation("intent.route")
                    .build(), ex);
            result = IntentRecognitionResult.degraded(new IntentDecision(
                    "finance.runtime.degraded",
                    "意图服务不可用，转入 AgentRuntime",
                    TaskComplexity.COMPLEX,
                    0.0,
                    false,
                    null,
                    Map.of(),
                    List.of(),
                    Map.of("source", "intent-agent-degraded",
                            "reason", ex.getMessage() == null ? "" : ex.getMessage())
            ));
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new IntentAgentRouteResult(result, latencyMs);
    }

    private java.util.Optional<RuntimeEvent> startFrame(IntentAgentRouteRequest request) {
        if (request == null || request.runId() == null || request.runId().isBlank()
                || request.session() == null || request.session().id() == null || request.session().id().isBlank()) {
            return java.util.Optional.empty();
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", PROVIDER);
        payload.put("sourceType", "intent-start");
        payload.put("stage", "intent_calling");
        payload.put("message", "正在识别问题意图");
        payload.put("routeTrigger", request.routeTrigger() == null ? "" : request.routeTrigger());
        return java.util.Optional.of(RuntimeEvent.progress(request.runId(), request.session().id(), Map.copyOf(payload)));
    }
}
