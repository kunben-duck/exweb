/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.notification;

import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;

import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.function.Consumer;

/** 基础设施端口；发布由后台执行器调用，订阅仅在监听准备完成后返回句柄。 */
public interface UserNotificationBus {
    void publish(UserNotificationRecipient recipient, String frozenNotification);

    Mono<Disposable> subscribe(UserNotificationRecipient recipient, Consumer<UserNotification> consumer);
}
