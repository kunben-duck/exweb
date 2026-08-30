/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 意图路由记忆配置。
 */
@Component
@ConfigurationProperties(prefix = "financeex.route-memory")
public class RouteMemoryProperties {
    /** 传给意图服务的最近可见路由记录条数。 */
    private int topK = 5;
    /** 意图澄清最多允许连续轮数，超过后降级到 Relay Runtime。 */
    private int maxClarificationRounds = 3;
    /** RouteMemory 只增强意图上下文，读取超过该时长即降级为空 history。 */
    private Duration timeout = Duration.ofMillis(300);
    /** RouteMemory 只增强意图上下文，读取超过该时长即降级为空 history。 */
    private Duration readTimeout = Duration.ofMillis(300);
    /** RouteMemory 旧版单线程池配置，保留 setter 兼容外部配置，内部会拆分到读写线程池。 */
    private Executor executor = new Executor();
    /** RouteMemory 读取线程池；队列保持较小，避免慢 DB 查询在后台无限堆积。 */
    private Executor readExecutor = new Executor(1, 2, 64);
    /** RouteMemory 写入线程池；写入是 best-effort，和读取隔离，避免慢读拖住成功路由记录。 */
    private Executor writeExecutor = new Executor(1, 1, 1000);
    /** RouteMemory 读取熔断配置。 */
    private CircuitBreaker circuitBreaker = new CircuitBreaker();
    private boolean readTimeoutExplicit;

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMaxClarificationRounds() {
        return maxClarificationRounds;
    }

    public void setMaxClarificationRounds(int maxClarificationRounds) {
        this.maxClarificationRounds = maxClarificationRounds;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
        if (!readTimeoutExplicit) {
            this.readTimeout = timeout;
        }
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
        this.readTimeoutExplicit = true;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor == null ? new Executor() : executor;
    }

    public Executor getReadExecutor() {
        return readExecutor;
    }

    public void setReadExecutor(Executor readExecutor) {
        this.readExecutor = readExecutor == null ? new Executor(1, 2, 64) : readExecutor;
    }

    public Executor getWriteExecutor() {
        return writeExecutor;
    }

    public void setWriteExecutor(Executor writeExecutor) {
        this.writeExecutor = writeExecutor == null ? new Executor(1, 1, 1000) : writeExecutor;
    }

    public CircuitBreaker getCircuitBreaker() {
        return circuitBreaker;
    }

    public void setCircuitBreaker(CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker == null ? new CircuitBreaker() : circuitBreaker;
    }

    public int normalizedTopK() {
        return Math.max(0, topK);
    }

    public int normalizedMaxClarificationRounds() {
        return Math.max(1, maxClarificationRounds);
    }

    public Duration normalizedTimeout() {
        return normalizedReadTimeout();
    }

    public Duration normalizedReadTimeout() {
        Duration effective = readTimeout == null ? timeout : readTimeout;
        if (effective == null) {
            effective = Duration.ofMillis(300);
        }
        return effective.isZero() || effective.isNegative()
                ? Duration.ofMillis(300)
                : effective;
    }

    public int normalizedCircuitBreakerFailureThreshold() {
        return circuitBreaker.normalizedFailureThreshold();
    }

    public Duration normalizedCircuitBreakerOpenDuration() {
        return circuitBreaker.normalizedOpenDuration();
    }

    @Deprecated
    public Duration legacyNormalizedTimeout() {
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofMillis(300)
                : timeout;
    }

    public static class Executor {
        private int coreSize = 1;
        private int maxSize = 2;
        private int queueCapacity = 1000;

        public Executor() {
        }

        public Executor(int coreSize, int maxSize, int queueCapacity) {
            this.coreSize = coreSize;
            this.maxSize = maxSize;
            this.queueCapacity = queueCapacity;
        }

        public int getCoreSize() {
            return coreSize;
        }

        public void setCoreSize(int coreSize) {
            this.coreSize = coreSize;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(int maxSize) {
            this.maxSize = maxSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int normalizedCoreSize() {
            return Math.max(1, coreSize);
        }

        public int normalizedMaxSize() {
            return Math.max(normalizedCoreSize(), maxSize);
        }

        public int normalizedQueueCapacity() {
            return Math.max(0, queueCapacity);
        }
    }

    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private Duration openDuration = Duration.ofSeconds(30);

        public int getFailureThreshold() {
            return failureThreshold;
        }

        public void setFailureThreshold(int failureThreshold) {
            this.failureThreshold = failureThreshold;
        }

        public Duration getOpenDuration() {
            return openDuration;
        }

        public void setOpenDuration(Duration openDuration) {
            this.openDuration = openDuration;
        }

        public int normalizedFailureThreshold() {
            return Math.max(1, failureThreshold);
        }

        public Duration normalizedOpenDuration() {
            return openDuration == null || openDuration.isZero() || openDuration.isNegative()
                    ? Duration.ofSeconds(30)
                    : openDuration;
        }
    }
}
