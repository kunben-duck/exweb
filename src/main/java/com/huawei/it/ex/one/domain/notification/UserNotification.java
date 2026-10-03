/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.domain.notification;

import java.util.Map;

/** 轻量变更提示，不携带 Run 事件序号，也不代表可靠送达。 */
public record UserNotification(String type, Map<String, Object> data) {
    public static final int MAX_SERIALIZED_BYTES = 16 * 1024;
}
