package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.application.config.RunAdmissionProperties;
import com.huawei.finance.front.one.domain.auth.UserContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * run 创建入口准入控制。
 *
 * <p>该服务用于保护当前 JVM：按用户限制创建速率，按租户限制本机并发 run 数。
 * 同一会话 active run 互斥由 {@link ChatRunApplicationService} 通过 Redis active key 与 openGauss
 * 状态共同保证。</p>
 */
@Service
public class RunAdmissionControlService {
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final RunAdmissionProperties properties;
    private final Clock clock;
    private final Map<String, Deque<Instant>> userWindows = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> tenantSemaphores = new ConcurrentHashMap<>();

    @Autowired
    public RunAdmissionControlService(RunAdmissionProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RunAdmissionControlService(RunAdmissionProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 为一次后台 run 获取准入许可。
     *
     * @param user 当前用户身份快照。
     * @return 需要在 run 结束时关闭的许可；配置关闭时返回 no-op 许可。
     */
    public Permit acquire(UserContext user) {
        if (!properties.isEnabled()) {
            return Permit.NOOP;
        }
        enforceUserRate(user);
        Semaphore tenantSemaphore = tenantSemaphores.computeIfAbsent(user.tenantId(),
                ignored -> new Semaphore(properties.normalizedMaxConcurrentRunsPerTenant()));
        if (!tenantSemaphore.tryAcquire()) {
            throw new IllegalStateException("TENANT_RUN_CONCURRENCY_EXCEEDED: 当前租户运行中任务过多，请稍后重试");
        }
        return tenantSemaphore::release;
    }

    private void enforceUserRate(UserContext user) {
        String key = user.tenantId() + ":" + user.userId();
        Instant now = Instant.now(clock);
        Deque<Instant> window = userWindows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (window) {
            Instant earliest = now.minus(RATE_WINDOW);
            while (!window.isEmpty() && window.peekFirst().isBefore(earliest)) {
                window.removeFirst();
            }
            if (window.size() >= properties.normalizedMaxRunsPerUserPerMinute()) {
                throw new IllegalStateException("RUN_RATE_LIMITED: 当前用户提问过于频繁，请稍后重试");
            }
            window.addLast(now);
        }
    }

    /**
     * 清理已经完全滑出限流窗口的用户速率记录。
     *
     * <p>限流状态只需要保存最近一分钟的请求时间。生产环境中的用户 ID 基数可能很大，
     * 如果不清理空窗口，长期运行后当前 JVM 会积累无业务价值的历史用户 key。</p>
     */
    @Scheduled(fixedDelay = 60_000L)
    void cleanupExpiredUserWindows() {
        Instant earliest = Instant.now(clock).minus(RATE_WINDOW);
        userWindows.entrySet().removeIf(entry -> {
            Deque<Instant> window = entry.getValue();
            synchronized (window) {
                while (!window.isEmpty() && window.peekFirst().isBefore(earliest)) {
                    window.removeFirst();
                }
                return window.isEmpty();
            }
        });
    }

    /**
     * run 准入许可。
     */
    public interface Permit extends AutoCloseable {
        Permit NOOP = () -> { };

        @Override
        void close();
    }
}
