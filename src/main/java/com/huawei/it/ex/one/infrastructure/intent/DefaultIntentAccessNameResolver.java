/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.intent;

import com.huawei.it.ex.one.application.integration.intent.IntentAccessNameResolver;

import org.springframework.stereotype.Component;

/** Resolves request-level Intent entries against the configured default. */
@Component
public class DefaultIntentAccessNameResolver implements IntentAccessNameResolver {
    private final IntentServiceHttpProperties properties;

    public DefaultIntentAccessNameResolver(IntentServiceHttpProperties properties) {
        this.properties = properties;
    }

    @Override
    public String resolve(String requestedAccessName) {
        if (requestedAccessName != null && !requestedAccessName.isBlank()) {
            return requestedAccessName.trim();
        }
        String configured = properties.getAccessName();
        return configured == null ? "" : configured.trim();
    }
}
