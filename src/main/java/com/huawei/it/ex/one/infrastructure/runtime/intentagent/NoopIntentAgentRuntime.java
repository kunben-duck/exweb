package com.huawei.it.ex.one.infrastructure.runtime.intentagent;

import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteFrame;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteRequest;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRouteResult;
import com.huawei.it.ex.one.application.integration.intent.IntentAgentRuntime;
import com.huawei.it.ex.one.application.integration.intent.IntentRecognitionResult;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.intent.TaskComplexity;
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
