package com.huawei.it.ex.one.chat.application.coordinator;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.runtime.application.model.RuntimeBinding;
import com.huawei.it.ex.one.runtime.application.service.RuntimeBindingService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Scheduler;

/** Performs the existing post-commit, best-effort RuntimeBinding cache synchronization. */
@Component
public class RuntimeBindingCacheSynchronizer {
    private static final AppLogger log = AppLoggerFactory.getLogger(RuntimeBindingCacheSynchronizer.class);
    private final RuntimeBindingService runtimeBindingService;
    private final Scheduler scheduler;

    public RuntimeBindingCacheSynchronizer(
            RuntimeBindingService runtimeBindingService,
            @Qualifier("domainAgentControlIoScheduler") Scheduler scheduler) {
        this.runtimeBindingService = runtimeBindingService;
        this.scheduler = scheduler;
    }

    public void schedule(RuntimeBinding binding) {
        if (binding == null) {
            return;
        }
        try {
            scheduler.schedule(() -> synchronize(binding));
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.TASK_REJECTED,
                            "RuntimeBinding cache sync task was rejected after database commit")
                    .sessionId(binding.chatSessionId())
                    .operation("runtime-binding.cache-sync-schedule")
                    .attribute("bindingId", binding.id())
                    .build(), ex);
        }
    }

    private void synchronize(RuntimeBinding binding) {
        try {
            runtimeBindingService.synchronizeCache(binding);
        } catch (RuntimeException ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_CACHE_SYNC_FAILED,
                            "RuntimeBinding cache sync failed after database commit")
                    .sessionId(binding.chatSessionId())
                    .operation("runtime-binding.cache-sync")
                    .attribute("bindingId", binding.id())
                    .build(), ex);
        }
    }
}
