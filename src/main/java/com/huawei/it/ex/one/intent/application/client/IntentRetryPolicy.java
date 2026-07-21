package com.huawei.it.ex.one.intent.application.client;

import com.huawei.it.ex.one.intent.application.model.IntentRetryContext;

/**
 * 意图识别重试策略端口。
 *
 * <p>HTTP 适配器只负责执行调用；哪些响应需要重试由该防腐层判断。后续如果需要按错误码、
 * 租户、接口版本或灰度策略调整重试规则，只替换该 port 的实现，不改财经 Eureka HTTP 适配器。</p>
 */
public interface IntentRetryPolicy {
    /**
     * 判断当前识别结果是否应该重试。
     *
     * @param context 本次识别调用的重试判定上下文。
     * @return true 表示应该继续重试；调用方仍会受最大重试次数保护。
     */
    boolean shouldRetry(IntentRetryContext context);
}
