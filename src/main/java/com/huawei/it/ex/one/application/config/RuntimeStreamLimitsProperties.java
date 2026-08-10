package com.huawei.it.ex.one.application.config;

import jakarta.validation.constraints.AssertTrue;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Relay与DomainAgent流式内存硬边界配置。 */
@Component
@Validated
@ConfigurationProperties(prefix = "financeex.agent-runtime.stream-limits")
public class RuntimeStreamLimitsProperties {
    private int pendingMaxEventsPerRun = 512;
    private DataSize pendingMaxBytesPerRun = DataSize.ofMegabytes(4);
    private int pendingMaxEventsPerInstance = 8_192;
    private DataSize pendingMaxBytesPerInstance = DataSize.ofMegabytes(64);
    private int assistantMaxPartsPerRun = 10_000;
    private DataSize assistantMaxBytesPerRun = DataSize.ofMegabytes(16);
    private int assistantProcessMaxRatio = 25;
    private int assistantMaxActivePartsPerInstance = 100_000;
    private DataSize assistantMaxActiveBytesPerInstance = DataSize.ofMegabytes(256);
    private Duration overflowCancelTimeout = Duration.ofSeconds(5);
    private Duration stopOwnerHandoffTimeout = Duration.ofSeconds(2);
    private Duration stopFinalizationLease = Duration.ofSeconds(15);
    private int stopReplayPageSize = 16;
    private int stopReplayMaxEventsPerRun = 10_000;
    private int stopReplayMaxConcurrency = 2;
    private int stopReplayQueryTimeoutSeconds = 2;
    private Duration stopReplayTotalTimeout = Duration.ofSeconds(5);

    public int getPendingMaxEventsPerRun() {
        return pendingMaxEventsPerRun;
    }

    public void setPendingMaxEventsPerRun(int pendingMaxEventsPerRun) {
        this.pendingMaxEventsPerRun = pendingMaxEventsPerRun;
    }

    public DataSize getPendingMaxBytesPerRun() {
        return pendingMaxBytesPerRun;
    }

    public void setPendingMaxBytesPerRun(DataSize pendingMaxBytesPerRun) {
        this.pendingMaxBytesPerRun = pendingMaxBytesPerRun;
    }

    public int getPendingMaxEventsPerInstance() {
        return pendingMaxEventsPerInstance;
    }

    public void setPendingMaxEventsPerInstance(int pendingMaxEventsPerInstance) {
        this.pendingMaxEventsPerInstance = pendingMaxEventsPerInstance;
    }

    public DataSize getPendingMaxBytesPerInstance() {
        return pendingMaxBytesPerInstance;
    }

    public void setPendingMaxBytesPerInstance(DataSize pendingMaxBytesPerInstance) {
        this.pendingMaxBytesPerInstance = pendingMaxBytesPerInstance;
    }

    public int getAssistantMaxPartsPerRun() {
        return assistantMaxPartsPerRun;
    }

    public void setAssistantMaxPartsPerRun(int assistantMaxPartsPerRun) {
        this.assistantMaxPartsPerRun = assistantMaxPartsPerRun;
    }

    public DataSize getAssistantMaxBytesPerRun() {
        return assistantMaxBytesPerRun;
    }

    public void setAssistantMaxBytesPerRun(DataSize assistantMaxBytesPerRun) {
        this.assistantMaxBytesPerRun = assistantMaxBytesPerRun;
    }

    public int getAssistantProcessMaxRatio() {
        return assistantProcessMaxRatio;
    }

    public void setAssistantProcessMaxRatio(int assistantProcessMaxRatio) {
        this.assistantProcessMaxRatio = assistantProcessMaxRatio;
    }

    public int getAssistantMaxActivePartsPerInstance() {
        return assistantMaxActivePartsPerInstance;
    }

    public void setAssistantMaxActivePartsPerInstance(int assistantMaxActivePartsPerInstance) {
        this.assistantMaxActivePartsPerInstance = assistantMaxActivePartsPerInstance;
    }

    public DataSize getAssistantMaxActiveBytesPerInstance() {
        return assistantMaxActiveBytesPerInstance;
    }

    public void setAssistantMaxActiveBytesPerInstance(DataSize assistantMaxActiveBytesPerInstance) {
        this.assistantMaxActiveBytesPerInstance = assistantMaxActiveBytesPerInstance;
    }

    public Duration getOverflowCancelTimeout() {
        return overflowCancelTimeout;
    }

    public void setOverflowCancelTimeout(Duration overflowCancelTimeout) {
        this.overflowCancelTimeout = overflowCancelTimeout;
    }

    public Duration getStopOwnerHandoffTimeout() {
        return stopOwnerHandoffTimeout;
    }

    public void setStopOwnerHandoffTimeout(Duration stopOwnerHandoffTimeout) {
        this.stopOwnerHandoffTimeout = stopOwnerHandoffTimeout;
    }

    public Duration getStopFinalizationLease() {
        return stopFinalizationLease;
    }

    public void setStopFinalizationLease(Duration stopFinalizationLease) {
        this.stopFinalizationLease = stopFinalizationLease;
    }

    public int getStopReplayPageSize() {
        return stopReplayPageSize;
    }

    public void setStopReplayPageSize(int stopReplayPageSize) {
        this.stopReplayPageSize = stopReplayPageSize;
    }

    public int getStopReplayMaxEventsPerRun() {
        return stopReplayMaxEventsPerRun;
    }

    public void setStopReplayMaxEventsPerRun(int stopReplayMaxEventsPerRun) {
        this.stopReplayMaxEventsPerRun = stopReplayMaxEventsPerRun;
    }

    public int getStopReplayMaxConcurrency() {
        return stopReplayMaxConcurrency;
    }

    public void setStopReplayMaxConcurrency(int stopReplayMaxConcurrency) {
        this.stopReplayMaxConcurrency = stopReplayMaxConcurrency;
    }

    public int getStopReplayQueryTimeoutSeconds() {
        return stopReplayQueryTimeoutSeconds;
    }

    public void setStopReplayQueryTimeoutSeconds(int stopReplayQueryTimeoutSeconds) {
        this.stopReplayQueryTimeoutSeconds = stopReplayQueryTimeoutSeconds;
    }

    public Duration getStopReplayTotalTimeout() {
        return stopReplayTotalTimeout;
    }

    public void setStopReplayTotalTimeout(Duration stopReplayTotalTimeout) {
        this.stopReplayTotalTimeout = stopReplayTotalTimeout;
    }

    public long pendingMaxBytesPerRun() {
        return pendingMaxBytesPerRun.toBytes();
    }

    public long pendingMaxBytesPerInstance() {
        return pendingMaxBytesPerInstance.toBytes();
    }

    public long assistantMaxBytesPerRun() {
        return assistantMaxBytesPerRun.toBytes();
    }

    public long assistantMaxActiveBytesPerInstance() {
        return assistantMaxActiveBytesPerInstance.toBytes();
    }

    public int assistantProcessMaxPartsPerRun() {
        return ratioOf(assistantMaxPartsPerRun);
    }

    public long assistantProcessMaxBytesPerRun() {
        return ratioOf(assistantMaxBytesPerRun());
    }

    public int assistantProcessMaxPartsPerInstance() {
        return ratioOf(assistantMaxActivePartsPerInstance);
    }

    public long assistantProcessMaxBytesPerInstance() {
        return ratioOf(assistantMaxActiveBytesPerInstance());
    }

    private int ratioOf(int value) {
        return (int) Math.min(Integer.MAX_VALUE, ratioOf((long) value));
    }

    private long ratioOf(long value) {
        return value * assistantProcessMaxRatio / 100L;
    }

    @AssertTrue(message = "financeex.agent-runtime.stream-limits配置非法")
    public boolean isValid() {
        return pendingMaxEventsPerRun > 0
                && positive(pendingMaxBytesPerRun)
                && pendingMaxEventsPerInstance >= pendingMaxEventsPerRun
                && positive(pendingMaxBytesPerInstance)
                && pendingMaxBytesPerInstance.toBytes() >= pendingMaxBytesPerRun.toBytes()
                && assistantMaxPartsPerRun > 0
                && positive(assistantMaxBytesPerRun)
                && assistantProcessMaxRatio >= 0 && assistantProcessMaxRatio <= 100
                && assistantMaxActivePartsPerInstance >= assistantMaxPartsPerRun
                && positive(assistantMaxActiveBytesPerInstance)
                && assistantMaxActiveBytesPerInstance.toBytes() >= assistantMaxBytesPerRun.toBytes()
                && overflowCancelTimeout != null
                && !overflowCancelTimeout.isZero()
                && !overflowCancelTimeout.isNegative()
                && positive(stopOwnerHandoffTimeout)
                && positive(stopFinalizationLease)
                && stopFinalizationLease.compareTo(stopOwnerHandoffTimeout) > 0
                && stopReplayPageSize > 0
                && stopReplayMaxEventsPerRun >= stopReplayPageSize
                && stopReplayMaxConcurrency > 0
                && stopReplayQueryTimeoutSeconds > 0
                && positive(stopReplayTotalTimeout)
                && stopReplayTotalTimeout.compareTo(Duration.ofSeconds(stopReplayQueryTimeoutSeconds)) >= 0;
    }

    private boolean positive(DataSize value) {
        return value != null && value.toBytes() > 0;
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
