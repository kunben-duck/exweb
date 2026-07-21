package com.huawei.it.ex.one.intent.infrastructure.runtime;

import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteFrame;
import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteRequest;
import com.huawei.it.ex.one.intent.application.model.IntentAgentRouteResult;
import com.huawei.it.ex.one.intent.application.client.IntentAgentRuntime;
import com.huawei.it.ex.one.intent.application.model.IntentRecognitionResult;
import com.huawei.it.ex.one.intent.application.model.IntentDecision;
import com.huawei.it.ex.one.intent.application.model.TaskComplexity;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * intent-agent 缺省实现。
 *
 * <p>RouteSignalApplicationService 在意图关闭时不会调用该实现；这里仅作为 Spring 依赖兜底，
 * 避免关闭意图时仍强制注册真实 HTTP adapter。</p>
 */
public class NoopIntentAgentRuntime implements IntentAgentRuntime {
    @Override
    public Flux<IntentAgentRouteFrame> route(IntentAgentRouteRequest request) {
        IntentRecognitionResult result = IntentRecognitionResult.degraded(new IntentDecision(
                "finance.runtime.intent.disabled",
                "意图服务未启用，转入 AgentRuntime",
                TaskComplexity.COMPLEX,
                0.0,
                false,
                null,
                Map.of(),
                List.of(),
                Map.of("source", "intent-agent-disabled")
        ));
        return Flux.just(IntentAgentRouteFrame.result(new IntentAgentRouteResult(result, 0L)));
    }
}
