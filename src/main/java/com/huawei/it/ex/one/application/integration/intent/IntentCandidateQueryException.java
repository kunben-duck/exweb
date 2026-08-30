/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.intent;

/** Intent候选技能查询失败。 */
public final class IntentCandidateQueryException extends RuntimeException {
    private final FailureType failureType;
    private final boolean retryable;

    private IntentCandidateQueryException(FailureType failureType,
                                          boolean retryable,
                                          String message,
                                          Throwable cause) {
        super(message, cause);
        this.failureType = failureType;
        this.retryable = retryable;
    }

    public static IntentCandidateQueryException timeout(Throwable cause) {
        return new IntentCandidateQueryException(
                FailureType.TIMEOUT,
                true,
                "Intent候选技能查询超时",
                cause);
    }

    public static IntentCandidateQueryException busy() {
        return new IntentCandidateQueryException(
                FailureType.BUSY,
                false,
                "Intent候选技能查询繁忙，请稍后重试",
                null);
    }

    public static IntentCandidateQueryException upstream(String message) {
        return upstream(message, null);
    }

    public static IntentCandidateQueryException upstream(String message, Throwable cause) {
        return new IntentCandidateQueryException(
                FailureType.UPSTREAM,
                false,
                message == null || message.isBlank() ? "Intent候选技能服务调用失败" : message,
                cause);
    }

    public static IntentCandidateQueryException retryableUpstream(String message, Throwable cause) {
        return new IntentCandidateQueryException(
                FailureType.UPSTREAM,
                true,
                message == null || message.isBlank() ? "Intent候选技能服务调用失败" : message,
                cause);
    }

    public boolean timeout() {
        return failureType == FailureType.TIMEOUT;
    }

    public boolean isBusy() {
        return failureType == FailureType.BUSY;
    }

    public boolean retryable() {
        return retryable;
    }

    private enum FailureType {
        BUSY,
        TIMEOUT,
        UPSTREAM
    }
}
