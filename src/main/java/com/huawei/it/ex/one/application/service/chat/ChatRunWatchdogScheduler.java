package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Chat run 故障治理巡检调度器。
 *
 * <p>该类只负责“什么时候触发巡检”，不包含恢复策略和 SQL 逻辑。具体 stale run 发现、抢占和恢复
 * 由 {@link ChatRunRecoveryOrchestrator} 完成。</p>
 */
@Component
public class ChatRunWatchdogScheduler {
    private static final AppLogger log = AppLoggerFactory.getLogger(ChatRunWatchdogScheduler.class);

    private final ChatRunOperationalProperties properties;
    private final ChatRunRecoveryOrchestrator recoveryOrchestrator;
    private final TaskScheduler taskScheduler;
    private final AtomicBoolean scanScheduledOrRunning = new AtomicBoolean(false);

    public ChatRunWatchdogScheduler(ChatRunOperationalProperties properties,
                                    ChatRunRecoveryOrchestrator recoveryOrchestrator,
                                    TaskScheduler taskScheduler) {
        this.properties = properties;
        this.recoveryOrchestrator = recoveryOrchestrator;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 周期性触发 stale run 巡检。
     *
     * <p>这里不直接 sleep 等待 jitter，而是把真正扫描延迟投递到调度线程池。这样即使开启随机抖动，
     * 也不会占住 {@code @Scheduled} 触发线程，避免影响 heartbeat 等其他治理任务。</p>
     */
    @Scheduled(
            initialDelayString = "#{@chatRunOperationalProperties.normalizedWatchdogInitialDelay().toMillis()}",
            fixedDelayString = "#{@chatRunOperationalProperties.normalizedWatchdogScanInterval().toMillis()}"
    )
    void scanExpiredRuns() {
        if (!properties.isWatchdogEnabled()) {
            return;
        }
        if (!scanScheduledOrRunning.compareAndSet(false, true)) {
            log.debug("ChatRun watchdog scan skipped because previous scan is still scheduled or running.");
            return;
        }
        try {
            taskScheduler.schedule(this::runScanSafely, Instant.now().plusMillis(nextJitterMillis()));
        } catch (RuntimeException ex) {
            scanScheduledOrRunning.set(false);
            log.warn("ChatRun watchdog scan schedule failed. reason={}", ex.getMessage(), ex);
        }
    }

    private void runScanSafely() {
        try {
            if (!properties.isWatchdogEnabled()) {
                return;
            }
            int recovered = recoveryOrchestrator.recoverExpiredRuns();
            if (recovered > 0) {
                log.info("ChatRun watchdog recovered stale runs. count={}", recovered);
            }
        } catch (RuntimeException ex) {
            log.warn("ChatRun watchdog scan failed. reason={}", ex.getMessage(), ex);
        } finally {
            scanScheduledOrRunning.set(false);
        }
    }

    private long nextJitterMillis() {
        Duration jitter = properties.normalizedWatchdogJitter();
        if (jitter.isZero() || jitter.isNegative()) {
            return 0L;
        }
        long bound = jitter.toMillis();
        if (bound <= 0) {
            return 0L;
        }
        return ThreadLocalRandom.current().nextLong(bound + 1);
    }
}
