package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Runtime 原始流响应日志配置。
 *
 * <p>该配置只控制下游 Runtime 原始响应的诊断日志，不影响 ChatService 标准事件、
 * WebSocket 实时推送、Event Resume 或 assistant 历史消息落库。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.runtime-raw-log")
public class RuntimeRawStreamLogProperties {
    /** 是否记录 Runtime 原始响应日志；关闭后不访问 raw log 表。 */
    private boolean enabled = false;
    /** 原始日志传输方式；disabled 表示关闭，其他值由企业自定义 publisher 解释。 */
    private String transport = "disabled";
    /** 原始 chunk 合并窗口；窗口到期后会 flush 一行 raw log。 */
    private Duration coalesceWindow = Duration.ofMillis(100);
    /** 单行 raw_content 最大保存字符数；普通超大 chunk 会按该长度分片保存。 */
    private int maxChars = 4096;
    /** 单个原始 chunk 的硬保护长度；超过后会截断并标记 truncated。 */
    private int hardMaxChars = 65536;
    /** 单个 run 最多保存 raw log 行数；超过后丢弃后续 raw log，不影响主链路。 */
    private int maxRowsPerRun = 1000;
    /** 是否对 raw_content 中明显敏感字段做脱敏。 */
    private boolean redactSensitiveFields = true;
    /** 消费端 run buffer 空闲保留时间；超时后会 flush 并释放内存。 */
    private Duration consumerStateIdleTtl = Duration.ofMinutes(5);
    /** 消费端最多同时保留的 run 合并 buffer 数，超过后新 run 降级为即时写入。 */
    private int consumerMaxActiveRunBuffers = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public Duration getCoalesceWindow() {
        return coalesceWindow;
    }

    public void setCoalesceWindow(Duration coalesceWindow) {
        this.coalesceWindow = coalesceWindow;
    }

    public int getMaxChars() {
        return maxChars;
    }

    public void setMaxChars(int maxChars) {
        this.maxChars = maxChars;
    }

    public int getHardMaxChars() {
        return hardMaxChars;
    }

    public void setHardMaxChars(int hardMaxChars) {
        this.hardMaxChars = hardMaxChars;
    }

    public int getMaxRowsPerRun() {
        return maxRowsPerRun;
    }

    public void setMaxRowsPerRun(int maxRowsPerRun) {
        this.maxRowsPerRun = maxRowsPerRun;
    }

    public boolean isRedactSensitiveFields() {
        return redactSensitiveFields;
    }

    public void setRedactSensitiveFields(boolean redactSensitiveFields) {
        this.redactSensitiveFields = redactSensitiveFields;
    }

    public Duration getConsumerStateIdleTtl() {
        return consumerStateIdleTtl;
    }

    public void setConsumerStateIdleTtl(Duration consumerStateIdleTtl) {
        this.consumerStateIdleTtl = consumerStateIdleTtl;
    }

    public int getConsumerMaxActiveRunBuffers() {
        return consumerMaxActiveRunBuffers;
    }

    public void setConsumerMaxActiveRunBuffers(int consumerMaxActiveRunBuffers) {
        this.consumerMaxActiveRunBuffers = consumerMaxActiveRunBuffers;
    }

    public Duration normalizedCoalesceWindow() {
        if (coalesceWindow == null || coalesceWindow.isNegative()) {
            return Duration.ofMillis(100);
        }
        return coalesceWindow;
    }

    public int normalizedMaxChars() {
        return Math.max(1, maxChars);
    }

    public int normalizedHardMaxChars() {
        return Math.max(normalizedMaxChars(), hardMaxChars);
    }

    public int normalizedMaxRowsPerRun() {
        return Math.max(0, maxRowsPerRun);
    }

    public Duration normalizedConsumerStateIdleTtl() {
        if (consumerStateIdleTtl == null || consumerStateIdleTtl.isNegative() || consumerStateIdleTtl.isZero()) {
            return Duration.ofMinutes(5);
        }
        return consumerStateIdleTtl;
    }

    public int normalizedConsumerMaxActiveRunBuffers() {
        return Math.max(1, consumerMaxActiveRunBuffers);
    }

    public boolean isDisabledTransport() {
        return "disabled".equalsIgnoreCase(transport == null ? "" : transport.trim());
    }
}
