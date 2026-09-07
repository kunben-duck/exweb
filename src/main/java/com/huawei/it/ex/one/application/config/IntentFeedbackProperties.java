/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Admission limits for the independent Intent feedback endpoints. */
@Validated
@ConfigurationProperties(prefix = "financeex.intent.feedback")
public class IntentFeedbackProperties {
    @Min(1)
    private int workerCount = 1;
    @Min(1)
    private int queueCapacity = 16;
    @NotNull
    private Duration queueWaitTimeout = Duration.ofMillis(500);

    public int getWorkerCount() {
        return workerCount;
    }

    public void setWorkerCount(int workerCount) {
        this.workerCount = workerCount;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getQueueWaitTimeout() {
        return queueWaitTimeout;
    }

    public void setQueueWaitTimeout(Duration queueWaitTimeout) {
        this.queueWaitTimeout = queueWaitTimeout;
    }

    @AssertTrue(message = "financeex.intent.feedback.queue-wait-timeout must be positive and representable in nanoseconds")
    public boolean isQueueWaitTimeoutValid() {
        try {
            return queueWaitTimeout != null && queueWaitTimeout.toNanos() > 0;
        } catch (ArithmeticException overflow) {
            return false;
        }
    }
}
