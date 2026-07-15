package com.huawei.it.ex.one.application.service.routing;

import com.huawei.it.ex.one.application.config.IntentFailureStrategy;
import com.huawei.it.ex.one.domain.intent.IntentDecision;
import com.huawei.it.ex.one.domain.routing.RouteTarget;
import java.util.Map;

/**
 * 首轮路由信号解析结果。
 *
 * <p>RouteTarget 描述本轮最终处理路径；IntentDecision 只在实际调用过意图服务时存在，
 * 用于继续传递给 AgentRuntime 或系统响应执行器。</p>
 *
 * @param route 本轮最终路由结果；FAIL_RUN 意图失败时为空。
 * @param intentDecision 意图服务识别结果；未调用意图服务时为空。
 * @param intentLatencyMs 意图服务调用耗时；未调用意图服务时为空。
 * @param intentConfidenceThreshold 兼容旧统计记录的意图阈值；不再参与 routeAction 裁决。
 * @param waitingIntentClarification 是否等待意图澄清。
 * @param intentClarificationPayload 意图澄清请求 payload。
 * @param intentSessionId 意图服务澄清会话 ID。
 * @param intentRequestId 意图服务澄清请求 ID。
 * @param intentFailureStrategy 意图失败时实际应用的策略；正常业务结果为空。
 * @param intentFailureReason 意图失败的内部诊断原因；正常业务结果为空。
 */
public record RouteSignalResult(
        RouteTarget route,
        IntentDecision intentDecision,
        Long intentLatencyMs,
        Double intentConfidenceThreshold,
        boolean waitingIntentClarification,
        Map<String, Object> intentClarificationPayload,
        String intentSessionId,
        String intentRequestId,
        IntentFailureStrategy intentFailureStrategy,
        String intentFailureReason
) {
    public RouteSignalResult {
        intentClarificationPayload = intentClarificationPayload == null ? Map.of() : Map.copyOf(intentClarificationPayload);
    }

    /**
     * 创建不包含意图识别结果的路由信号结果。
     *
     * @param route 本轮最终路由结果。
     * @return 路由信号结果。
     */
    public static RouteSignalResult of(RouteTarget route) {
        return new RouteSignalResult(route, null, null, null, false, Map.of(), null, null, null, null);
    }

    /**
     * 创建包含意图识别结果的路由信号结果。
     *
     * @param route 本轮最终路由结果。
     * @param intentDecision 意图识别结果。
     * @param intentLatencyMs 意图服务调用耗时。
     * @return 路由信号结果。
     */
    public static RouteSignalResult ofIntent(RouteTarget route, IntentDecision intentDecision, Long intentLatencyMs,
                                             double intentConfidenceThreshold) {
        return new RouteSignalResult(route, intentDecision, intentLatencyMs, intentConfidenceThreshold,
                false, Map.of(), null, null, null, null);
    }

    public static RouteSignalResult waitingIntentClarification(Map<String, Object> payload, Long intentLatencyMs,
                                                               double intentConfidenceThreshold,
                                                               String intentSessionId,
                                                               String intentRequestId) {
        return new RouteSignalResult(RouteTarget.systemResponse("intent clarification required"), null,
                intentLatencyMs, intentConfidenceThreshold, true, payload, intentSessionId, intentRequestId,
                null, null);
    }

    public static RouteSignalResult intentFailure(RouteTarget route, IntentDecision intentDecision,
                                                  Long intentLatencyMs, double intentConfidenceThreshold,
                                                  IntentFailure failure) {
        return new RouteSignalResult(route, intentDecision, intentLatencyMs, intentConfidenceThreshold,
                false, Map.of(), null, null,
                failure == null ? null : failure.strategy(),
                failure == null ? null : failure.reason());
    }

    public boolean intentFailure() {
        return intentFailureStrategy != null;
    }

    public boolean failRunOnIntentFailure() {
        return intentFailureStrategy == IntentFailureStrategy.FAIL_RUN;
    }

    public record IntentFailure(IntentFailureStrategy strategy, String reason) {
    }
}
