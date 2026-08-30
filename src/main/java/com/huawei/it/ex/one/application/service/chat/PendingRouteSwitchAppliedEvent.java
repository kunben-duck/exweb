/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import java.util.Objects;

/** Route-switch success fact deferred until its candidate binding commits. */
record PendingRouteSwitchAppliedEvent(
        ChatEvent event,
        String candidateBindingId
) {
    PendingRouteSwitchAppliedEvent {
        Objects.requireNonNull(event, "Pending route-switch event must not be null");
        if (!"runtime.metadata".equals(event.type())
                || event.payload() == null
                || !"route-switch-applied".equals(event.payload().get("sourceType"))) {
            throw new IllegalArgumentException("Pending route-switch event must be route-switch-applied metadata");
        }
        if (candidateBindingId == null || candidateBindingId.isBlank()) {
            throw new IllegalArgumentException("Pending route-switch event requires a candidate binding ID");
        }
    }
}
