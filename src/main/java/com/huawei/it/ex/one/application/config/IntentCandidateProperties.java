package com.huawei.it.ex.one.application.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Intent候选技能查询的本机资源隔离和退避配置。 */
@Validated
@ConfigurationProperties(prefix = "financeex.intent.candidate")
public class IntentCandidateProperties {
    @Min(1)
    private int maxConcurrency = 8;
    @Min(1)
    private int authIoMaxSize = 2;
    @Min(1)
    private int authIoQueueCapacity = 16;
    @NotNull
    private Duration retryMinBackoff = Duration.ofMillis(200);
    @NotNull
    private Duration retryMaxBackoff = Duration.ofSeconds(1);

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getAuthIoMaxSize() {
        return authIoMaxSize;
    }

    public void setAuthIoMaxSize(int authIoMaxSize) {
        this.authIoMaxSize = authIoMaxSize;
    }

    public int getAuthIoQueueCapacity() {
        return authIoQueueCapacity;
    }

    public void setAuthIoQueueCapacity(int authIoQueueCapacity) {
        this.authIoQueueCapacity = authIoQueueCapacity;
    }

    public Duration getRetryMinBackoff() {
        return retryMinBackoff;
    }

    public void setRetryMinBackoff(Duration retryMinBackoff) {
        this.retryMinBackoff = retryMinBackoff;
    }

    public Duration getRetryMaxBackoff() {
        return retryMaxBackoff;
    }

    public void setRetryMaxBackoff(Duration retryMaxBackoff) {
        this.retryMaxBackoff = retryMaxBackoff;
    }

    @AssertTrue(message = "financeex.intent.candidate durations must be positive and max backoff must not be less than min backoff")
    public boolean isRetryBackoffConfigurationValid() {
        return positive(retryMinBackoff)
                && positive(retryMaxBackoff)
                && retryMaxBackoff.compareTo(retryMinBackoff) >= 0;
    }

    @AssertTrue(message = "financeex.intent.candidate auth-io-max-size must not exceed max-concurrency")
    public boolean isAuthConcurrencyConfigurationValid() {
        return authIoMaxSize > 0 && maxConcurrency > 0 && authIoMaxSize <= maxConcurrency;
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
