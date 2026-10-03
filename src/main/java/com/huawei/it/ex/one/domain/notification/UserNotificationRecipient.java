/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.notification;

/** 通知接收方来自可信业务记录或 WebSocket 握手，不接受客户端指定。 */
public record UserNotificationRecipient(String tenantId, String userId) {
    public boolean valid() {
        return tenantId != null && !tenantId.isBlank() && userId != null && !userId.isBlank();
    }
}
