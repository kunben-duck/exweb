package com.huawei.finance.front.one.application.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Chat run 运行控制面配置。
 *
 * <p>这些配置只影响后台 run 的租约、watchdog 巡检和 stale run 恢复治理，不改变前端协议。</p>
 */
@Component
@ConfigurationProperties(prefix = "financeex.chat-run")
public class ChatRunOperationalProperties {
    /** run 执行租约时长；执行实例必须在该时间内续租，否则会被 watchdog 判定为 stale。 */
    private Duration leaseDuration = Duration.ofSeconds(90);
    /** 当前 owner 刷新 run 租约的心跳间隔。 */
    private Duration heartbeatInterval = Duration.ofSeconds(15);
    /** 是否启用后台 watchdog 巡检。 */
    private boolean watchdogEnabled = true;
    /** 应用 ready 后首次启动 watchdog 的延迟时间。 */
    private Duration watchdogInitialDelay = Duration.ofSeconds(30);
    /** watchdog 周期扫描间隔。 */
    private Duration watchdogScanInterval = Duration.ofSeconds(30);
    /** watchdog 每轮最多读取的 stale run 候选数量。 */
    private int watchdogBatchSize = 100;
    /** watchdog 每轮最多抢占的 run 数量，避免单实例恢复过载。 */
    private int watchdogMaxClaimsPerScan = 20;
    /** 当前 JVM 同时执行 stale run 恢复的最大数量。 */
    private int recoveryMaxConcurrency = 4;
    /** 当前 JVM 同时执行 Runtime 接管续跑的最大数量。 */
    private int takeoverMaxConcurrency = 1;
    /** watchdog 每轮每租户最多抢占数量，避免单租户 stale run 占满恢复能力。 */
    private int recoveryMaxClaimsPerTenantPerScan = 5;
    /** watchdog 周期扫描的随机抖动上限，降低多实例同一时刻打 DB 的概率。 */
    private Duration watchdogJitter = Duration.ofSeconds(5);
    /** stale run 恢复策略链，按顺序尝试。 */
    private List<String> staleRecoveryStrategies = List.of("MANUAL_CONFIRMATION", "FAIL_FAST");
    /** Redis recover lock 是否启用；禁用或 Redis 不可用时仍会走数据库条件抢占。 */
    private boolean recoverLockEnabled = true;
    /** Redis recover lock TTL，仅用于减少 DB 抢占冲突，不作为正确性事实源。 */
    private Duration recoverLockTtl = Duration.ofSeconds(30);
    /** run 已创建但 execution 尚未建立时，watchdog 开始回收的宽限期。 */
    private Duration executionInitOrphanGrace = Duration.ofMinutes(2);
    /** 创建 run 接口等待首个持久化事件的最长时间；非正数表示禁用。 */
    private Duration firstEventTimeout = Duration.ofSeconds(30);

    public Duration getLeaseDuration() {
        return leaseDuration;
    }

    public void setLeaseDuration(Duration leaseDuration) {
        this.leaseDuration = leaseDuration;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public boolean isWatchdogEnabled() {
        return watchdogEnabled;
    }

    public void setWatchdogEnabled(boolean watchdogEnabled) {
        this.watchdogEnabled = watchdogEnabled;
    }

    public Duration getWatchdogInitialDelay() {
        return watchdogInitialDelay;
    }

    public void setWatchdogInitialDelay(Duration watchdogInitialDelay) {
        this.watchdogInitialDelay = watchdogInitialDelay;
    }

    public Duration getWatchdogScanInterval() {
        return watchdogScanInterval;
    }

    public void setWatchdogScanInterval(Duration watchdogScanInterval) {
        this.watchdogScanInterval = watchdogScanInterval;
    }

    public int getWatchdogBatchSize() {
        return watchdogBatchSize;
    }

    public void setWatchdogBatchSize(int watchdogBatchSize) {
        this.watchdogBatchSize = watchdogBatchSize;
    }

    public int getWatchdogMaxClaimsPerScan() {
        return watchdogMaxClaimsPerScan;
    }

    public void setWatchdogMaxClaimsPerScan(int watchdogMaxClaimsPerScan) {
        this.watchdogMaxClaimsPerScan = watchdogMaxClaimsPerScan;
    }

    public int getRecoveryMaxConcurrency() {
        return recoveryMaxConcurrency;
    }

    public void setRecoveryMaxConcurrency(int recoveryMaxConcurrency) {
        this.recoveryMaxConcurrency = recoveryMaxConcurrency;
    }

    public int getTakeoverMaxConcurrency() {
        return takeoverMaxConcurrency;
    }

    public void setTakeoverMaxConcurrency(int takeoverMaxConcurrency) {
        this.takeoverMaxConcurrency = takeoverMaxConcurrency;
    }

    public int getRecoveryMaxClaimsPerTenantPerScan() {
        return recoveryMaxClaimsPerTenantPerScan;
    }

    public void setRecoveryMaxClaimsPerTenantPerScan(int recoveryMaxClaimsPerTenantPerScan) {
        this.recoveryMaxClaimsPerTenantPerScan = recoveryMaxClaimsPerTenantPerScan;
    }

    public Duration getWatchdogJitter() {
        return watchdogJitter;
    }

    public void setWatchdogJitter(Duration watchdogJitter) {
        this.watchdogJitter = watchdogJitter;
    }

    public List<String> getStaleRecoveryStrategies() {
        return staleRecoveryStrategies;
    }

    public void setStaleRecoveryStrategies(List<String> staleRecoveryStrategies) {
        this.staleRecoveryStrategies = staleRecoveryStrategies;
    }

    public boolean isRecoverLockEnabled() {
        return recoverLockEnabled;
    }

    public void setRecoverLockEnabled(boolean recoverLockEnabled) {
        this.recoverLockEnabled = recoverLockEnabled;
    }

    public Duration getRecoverLockTtl() {
        return recoverLockTtl;
    }

    public void setRecoverLockTtl(Duration recoverLockTtl) {
        this.recoverLockTtl = recoverLockTtl;
    }

    public Duration getExecutionInitOrphanGrace() {
        return executionInitOrphanGrace;
    }

    public void setExecutionInitOrphanGrace(Duration executionInitOrphanGrace) {
        this.executionInitOrphanGrace = executionInitOrphanGrace;
    }

    public Duration getFirstEventTimeout() {
        return firstEventTimeout;
    }

    public void setFirstEventTimeout(Duration firstEventTimeout) {
        this.firstEventTimeout = firstEventTimeout;
    }

    public Duration normalizedLeaseDuration() {
        return positiveOrDefault(leaseDuration, Duration.ofSeconds(90));
    }

    public Duration normalizedHeartbeatInterval() {
        return positiveOrDefault(heartbeatInterval, Duration.ofSeconds(15));
    }

    public Duration normalizedWatchdogInitialDelay() {
        return nonNegativeOrDefault(watchdogInitialDelay, Duration.ofSeconds(30));
    }

    public Duration normalizedWatchdogScanInterval() {
        return positiveOrDefault(watchdogScanInterval, Duration.ofSeconds(30));
    }

    public int normalizedWatchdogBatchSize() {
        return Math.max(1, watchdogBatchSize);
    }

    public int normalizedWatchdogMaxClaimsPerScan() {
        return Math.max(1, watchdogMaxClaimsPerScan);
    }

    public int normalizedRecoveryMaxConcurrency() {
        return Math.max(1, recoveryMaxConcurrency);
    }

    public int normalizedTakeoverMaxConcurrency() {
        return Math.max(1, takeoverMaxConcurrency);
    }

    public int normalizedRecoveryMaxClaimsPerTenantPerScan() {
        return Math.max(1, recoveryMaxClaimsPerTenantPerScan);
    }

    public Duration normalizedWatchdogJitter() {
        return nonNegativeOrDefault(watchdogJitter, Duration.ofSeconds(5));
    }

    public List<String> normalizedStaleRecoveryStrategies() {
        return staleRecoveryStrategies == null || staleRecoveryStrategies.isEmpty()
                ? List.of("MANUAL_CONFIRMATION", "FAIL_FAST")
                : staleRecoveryStrategies;
    }

    public Duration normalizedRecoverLockTtl() {
        return positiveOrDefault(recoverLockTtl, Duration.ofSeconds(30));
    }

    public Duration normalizedExecutionInitOrphanGrace() {
        return positiveOrDefault(executionInitOrphanGrace, Duration.ofMinutes(2));
    }

    public Duration normalizedFirstEventTimeout() {
        return firstEventTimeout == null ? Duration.ofSeconds(30) : firstEventTimeout;
    }

    private Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private Duration nonNegativeOrDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() ? fallback : value;
    }
}
