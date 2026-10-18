/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.observation;

import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;

import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicLong;

/** 限频且不携带业务数据、异常文本或高基数ID。 */
public final class ObservationDiagnostics {
    private static final AppLogger LOG = AppLoggerFactory.getLogger(ObservationDiagnostics.class);
    private final AtomicLong lastWarning = new AtomicLong();
    private final MeterRegistry registry;
    public ObservationDiagnostics(MeterRegistry registry) { this.registry = registry; }

    public void queue(java.util.concurrent.ThreadPoolExecutor executor) {
        try {
            if (registry != null) {
                registry.gauge("financeex.observation.queue.size", executor, pool -> pool.getQueue().size());
            }
        } catch (RuntimeException ignored) {
            // 指标注册失败不影响观测写入或业务启动。
        }
    }

    public void dropped(String reason) {
        try {
            if (registry != null) { registry.counter("financeex.observation.dropped", "reason", reason).increment(); }
            long now = System.nanoTime();
            long previous = lastWarning.get();
            if ((previous == 0 || now - previous > 60_000_000_000L) && lastWarning.compareAndSet(previous, now)) {
                LOG.warn("Observation discarded: reason={}", reason);
            }
        } catch (RuntimeException ignored) {
            // 观测告警本身不得成为业务异常。
        }
    }
}
