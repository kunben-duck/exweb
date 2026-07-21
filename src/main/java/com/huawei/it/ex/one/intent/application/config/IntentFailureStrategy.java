package com.huawei.it.ex.one.intent.application.config;

/**
 * 意图路由在重试耗尽后的处理策略。
 */
public enum IntentFailureStrategy {
    /** 自动进入 Relay Runtime 继续处理用户问题。 */
    RELAY_FALLBACK,
    /** 直接以 run.failed 结束，提示用户手动选择 DomainAgent。 */
    FAIL_RUN
}
