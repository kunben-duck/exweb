package com.huawei.finance.front.one.application.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 意图识别记录配置。
 *
 * <p>意图识别记录只用于统计和排障，是 best-effort 旁路能力。默认关闭，避免未建表或未评估
 * 数据量时改变生产主链路行为。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.intent-record")
public class IntentRecordProperties {
    /** 是否开启意图识别记录写入。 */
    private boolean enabled = false;
    /** query_text 最大保存长度，超过后截断。 */
    private int maxQueryLength = 4096;
    /** raw/items JSON 最大保存长度，超过后截断。 */
    private int maxRawJsonLength = 65536;
    /** Servlet 模式下意图记录专用异步线程池配置。 */
    private Executor executor = new Executor();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxQueryLength() {
        return maxQueryLength;
    }

    public void setMaxQueryLength(int maxQueryLength) {
        this.maxQueryLength = maxQueryLength;
    }

    public int getMaxRawJsonLength() {
        return maxRawJsonLength;
    }

    public void setMaxRawJsonLength(int maxRawJsonLength) {
        this.maxRawJsonLength = maxRawJsonLength;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor == null ? new Executor() : executor;
    }

    public int normalizedMaxQueryLength() {
        return Math.max(1, maxQueryLength);
    }

    public int normalizedMaxRawJsonLength() {
        return Math.max(1, maxRawJsonLength);
    }

    /**
     * 专用线程池配置。
     */
    public static class Executor {
        private int coreSize = 1;
        private int maxSize = 2;
        private int queueCapacity = 1000;

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
}
