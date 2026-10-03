/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.notification;

import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;

/** 业务侧入口：有事务时提交后投递，返回不表示客户端已收到。 */
@FunctionalInterface
public interface UserNotificationPublisher {
    void publish(UserNotificationRecipient recipient, UserNotification notification);
}
