package com.huawei.finance.front.one.infrastructure.runtime.intentagent;

import com.huawei.finance.front.one.application.integration.intent.IntentAgentRouteFrame;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRouteRequest;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRouteResult;
import com.huawei.finance.front.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.finance.front.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.domain.chat.RuntimeEvent;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(BlockingIntentAgentRuntime.class);

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
            log.warn("IntentAgent route failed, degrading to Relay Runtime. tenantId={}, userId={}, sessionId={}, reason={}",
                    request.user() == null ? null : request.user().tenantId(),
                    request.user() == null ? null : request.user().ownerUserId(),
                    request.session() == null ? null : request.session().id(),
                    ex.getMessage());
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
