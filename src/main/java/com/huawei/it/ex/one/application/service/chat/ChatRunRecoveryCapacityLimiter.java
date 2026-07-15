package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatRunOperationalProperties;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Service;

/**
 * stale run 恢复的本机并发治理器。
 *
 * <p>watchdog 发现大量 stale run 时，必须先确认本机有处理能力再抢占，避免把大量任务标记为
 * RECOVERING 后排队等待，从而形成新的单点压力或恢复中卡死。</p>
 */
@Service
public class ChatRunRecoveryCapacityLimiter {
    private final Semaphore recovery;
    private final Semaphore takeover;

    public ChatRunRecoveryCapacityLimiter(ChatRunOperationalProperties properties) {
        this.recovery = new Semaphore(properties.normalizedRecoveryMaxConcurrency());
        this.takeover = new Semaphore(properties.normalizedTakeoverMaxConcurrency());
    }

    /**
     * 尝试获取普通恢复许可。
     *
     * @return 许可；无容量时为空。
     */
    public Permit tryAcquireRecovery() {
        return tryAcquire(recovery);
    }

    /**
     * 尝试获取 Runtime takeover 许可。
     *
     * @return 许可；无容量时为空。
     */
    public Permit tryAcquireTakeover() {
        return tryAcquire(takeover);
    }

    private Permit tryAcquire(Semaphore semaphore) {
        if (!semaphore.tryAcquire()) {
            return null;
        }
        return semaphore::release;
    }

    /**
     * 需要在恢复动作完成后释放的本机容量许可。
     */
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }
}
