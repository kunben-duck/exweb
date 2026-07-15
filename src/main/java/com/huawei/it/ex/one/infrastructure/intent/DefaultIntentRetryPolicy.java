package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.integration.intent.IntentRetryContext;
import com.huawei.it.ex.one.application.integration.intent.IntentRetryPolicy;
import com.huawei.it.ex.one.domain.intent.IntentDecision;

/**
 * 默认意图识别重试策略。
 *
 * <p>策略只重试调用失败、协议错误或 HTTP 适配器降级结果；正常的“未识别到意图”是业务结果，
 * 不应通过重试放大下游压力。</p>
 */
public class DefaultIntentRetryPolicy implements IntentRetryPolicy {
    @Override
    public boolean shouldRetry(IntentRetryContext context) {
        if (context == null || !context.hasRemainingAttempts()) {
            return false;
        }
        IntentDecision decision = context.decision();
        if (decision == null) {
            return true;
        }
        String intentCode = decision.intentCode();
        if ("finance.runtime.degraded".equals(intentCode) || "finance.runtime.intent_error".equals(intentCode)) {
            return true;
        }
        return "http-intent-degraded".equals(decision.raw().get("source"));
    }
}
