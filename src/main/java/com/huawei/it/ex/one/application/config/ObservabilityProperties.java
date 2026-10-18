/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "financeex.observability")
public class ObservabilityProperties {
    private boolean enabled;
    @Valid
    private final Writer writer = new Writer();
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Writer getWriter() { return writer; }

    public static class Writer {
        @Min(1)
        private int workerCount = 1;
        @Min(1)
        private int queueCapacity = 1024;
        @NotNull
        private Duration queueWaitTimeout = Duration.ofSeconds(1);
        @NotNull
        private Duration transactionTimeout = Duration.ofSeconds(2);
        public int getWorkerCount() { return workerCount; }
        public void setWorkerCount(int value) { workerCount = value; }
        public int getQueueCapacity() { return queueCapacity; }
        public void setQueueCapacity(int value) { queueCapacity = value; }
        public Duration getQueueWaitTimeout() { return queueWaitTimeout; }
        public void setQueueWaitTimeout(Duration value) { queueWaitTimeout = value; }
        public Duration getTransactionTimeout() { return transactionTimeout; }
        public void setTransactionTimeout(Duration value) { transactionTimeout = value; }

        @AssertTrue(message = "observability writer timeouts must be positive and representable")
        public boolean isTimeoutsValid() {
            return positive(queueWaitTimeout) && queueWaitTimeout.compareTo(Duration.ofNanos(Long.MAX_VALUE)) <= 0
                    && positive(transactionTimeout)
                    && transactionTimeout.compareTo(Duration.ofSeconds(Integer.MAX_VALUE - 1L)) <= 0;
        }

        private boolean positive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }
    }
}
