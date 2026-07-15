package com.huawei.it.ex.one.application.integration.intent;

/**
 * IntentAgent 最终识别结果。
 *
 * @param recognitionResult 意图识别结果。
 * @param latencyMs 本次 IntentAgent 调用耗时，毫秒。
 */
public record IntentAgentRouteResult(
        IntentRecognitionResult recognitionResult,
        long latencyMs
) {
}
