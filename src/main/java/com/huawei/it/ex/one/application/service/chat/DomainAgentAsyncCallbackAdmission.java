/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.DomainAgentProperties;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-instance admission gate acquired before an async callback body is deserialized. */
@Component
public final class DomainAgentAsyncCallbackAdmission {
    private final Semaphore permits;

    public DomainAgentAsyncCallbackAdmission(DomainAgentProperties properties) {
        this.permits = new Semaphore(properties.requiredAsyncTaskCallbackMaxConcurrency(), true);
    }

    public Permit tryAcquire() {
        return permits.tryAcquire() ? new Permit(permits) : null;
    }

    public static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private final AtomicBoolean released = new AtomicBoolean();

        private Permit(Semaphore permits) {
            this.permits = permits;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
