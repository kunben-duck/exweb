/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.service.runtime.RuntimeBindingApplicationService;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.runtime.RuntimeBinding;

import reactor.core.scheduler.Scheduler;

/** Performs the existing post-commit, best-effort RuntimeBinding cache synchronization. */
final class RuntimeBindingCacheSynchronizer {
    private static final AppLogger log = AppLoggerFactory.getLogger(RuntimeBindingCacheSynchronizer.class);
    private final RuntimeBindingApplicationService runtimeBindingService;
    private volatile Scheduler scheduler;

    RuntimeBindingCacheSynchronizer(RuntimeBindingApplicationService runtimeBindingService,
                                    Scheduler scheduler) {
        this.runtimeBindingService = runtimeBindingService;
        this.scheduler = scheduler;
    }

    void setScheduler(Scheduler scheduler) {
        if (scheduler != null) {
            this.scheduler = scheduler;
        }
    }

    void schedule(RuntimeBinding binding) {
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
