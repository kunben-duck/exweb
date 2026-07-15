package com.huawei.it.ex.one.application.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

/**
 * 运行治理定时任务配置。
 *
 * <p>当前用于清理 MVC WebSocket 空闲连接、run 准入控制过期窗口、ChatRun 租约心跳
 * 以及 stale run watchdog 巡检。生产环境必须使用线程池调度器，避免 watchdog jitter 或慢 IO
 * 阻塞单线程 scheduler 后影响 heartbeat。</p>
 */
@Configuration
@EnableScheduling
public class OperationalSchedulingConfig implements SchedulingConfigurer {
    private final int poolSize;

    public OperationalSchedulingConfig(@Value("${financeex.scheduler.pool-size:4}") int poolSize) {
        this.poolSize = Math.max(2, poolSize);
    }

    /**
     * 运行治理定时任务线程池。
     *
     * @return 用于所有 {@code @Scheduled} 任务的统一调度器。
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler finExTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("finex-scheduled-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        TaskScheduler scheduler = finExTaskScheduler();
        taskRegistrar.setTaskScheduler(scheduler);
    }
}
