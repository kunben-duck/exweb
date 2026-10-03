/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.notification;

import com.huawei.it.ex.one.application.integration.notification.UserNotificationBus;
import com.huawei.it.ex.one.application.integration.notification.UserNotificationPublisher;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executor;

/** 冻结通知后登记提交回调；任何通知故障均不能改变业务提交结果。 */
@Service
public class DefaultUserNotificationPublisher implements UserNotificationPublisher {
    public static final int MAX_NOTIFICATION_BYTES = UserNotification.MAX_SERIALIZED_BYTES;
    private static final AppLogger log = AppLoggerFactory.getLogger(DefaultUserNotificationPublisher.class);

    private final UserNotificationBus bus;
    private final ObjectMapper objectMapper;
    private final Executor executor;

    public DefaultUserNotificationPublisher(UserNotificationBus bus, ObjectMapper objectMapper,
            @Qualifier("redisChatLivePublishExecutor") Executor executor) {
        this.bus = bus;
        this.objectMapper = objectMapper;
        this.executor = executor;
    }

    @Override
    public void publish(UserNotificationRecipient recipient, UserNotification notification) {
        try {
            if (recipient == null || !recipient.valid() || notification == null
                    || notification.type() == null || notification.type().isBlank()
                    || notification.type().length() > 128 || notification.data() == null) {
                throw new IllegalArgumentException("Invalid user notification");
            }
            // 序列化快照避免调用方修改嵌套 Map；有界输出避免先构建超大 JSON。
            LimitedOutput output = new LimitedOutput();
            objectMapper.writeValue(output, notification);
            String frozen = output.value();
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    throw new IllegalStateException("Transaction synchronization unavailable");
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        enqueue(recipient, frozen);
                    }
                });
            } else {
                enqueue(recipient, frozen);
            }
        } catch (Exception ex) {
            warn("user-notification.prepare", ex);
        }
    }

    private void enqueue(UserNotificationRecipient recipient, String frozen) {
        try {
            executor.execute(() -> {
                try {
                    bus.publish(recipient, frozen);
                } catch (RuntimeException ex) {
                    warn("user-notification.publish", ex);
                }
            });
        } catch (RuntimeException ex) {
            warn("user-notification.enqueue", ex);
        }
    }

    private static void warn(String operation, Exception ex) {
        // 不记录异常消息或堆栈，避免序列化异常包含业务内容。
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                        "User notification dropped; business result is unchanged")
                .operation(operation).attribute("failureType", ex.getClass().getSimpleName()).build());
    }

    private static final class LimitedOutput extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        @Override
        public void write(int value) throws IOException {
            checkSize(1);
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int offset, int length) throws IOException {
            checkSize(length);
            bytes.write(value, offset, length);
        }

        private void checkSize(int additional) throws IOException {
            if (additional > MAX_NOTIFICATION_BYTES - bytes.size()) {
                throw new IOException("User notification exceeds capacity");
            }
        }

        private String value() {
            return bytes.toString(StandardCharsets.UTF_8);
        }
    }
}
