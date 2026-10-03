/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.notification;

import com.huawei.it.ex.one.application.integration.notification.UserNotificationBus;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.domain.notification.UserNotification;
import com.huawei.it.ex.one.domain.notification.UserNotificationRecipient;
import com.huawei.it.ex.one.infrastructure.redis.FinanceExRedisKeyBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.SubscriptionListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** 用户级 best-effort 通道；同机也经 Redis 投递，不重复本地广播。 */
@Component
public class RedisUserNotificationBus implements UserNotificationBus {
    private static final AppLogger log = AppLoggerFactory.getLogger(RedisUserNotificationBus.class);
    private static final Duration REGISTRATION_TIMEOUT = Duration.ofSeconds(2);
    private static final int MAX_REGISTRATIONS = 4;
    private static final int CLEANUP_BATCH_SIZE = 16;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FinanceExRedisKeyBuilder keys;
    private final RedisMessageListenerContainer container;
    private final Map<UserNotificationRecipient, Listener> listeners = new ConcurrentHashMap<>();
    private final Semaphore registrations = new Semaphore(MAX_REGISTRATIONS);
    private final ThreadPoolExecutor lifecycleExecutor = newLifecycleExecutor();
    // 仅保护清理引用和调度状态，不在该锁内访问 Redis 或等待生命周期锁。
    private final Object cleanupMonitor = new Object();
    private final Set<Listener> pendingCleanup = new LinkedHashSet<>();
    private final Set<Listener> failedCleanup = new LinkedHashSet<>();
    private boolean cleanupScheduled;
    // add、最后监听移除和销毁必须串行，避免容器监听状态与 listenFuture 跨代交错。
    private final ReentrantLock registrationLock = new ReentrantLock(true);
    private volatile boolean closed;
    private boolean destroyed;

    @Autowired
    public RedisUserNotificationBus(StringRedisTemplate redis, ObjectMapper objectMapper,
            FinanceExRedisKeyBuilder keys, RedisConnectionFactory factory,
            @Qualifier("redisChatLivePublishExecutor") Executor executor) {
        this(redis, objectMapper, keys, new RedisMessageListenerContainer());
        container.setConnectionFactory(factory);
        container.setTaskExecutor(task -> dispatchNotificationTask(executor, task));
        container.setSubscriptionExecutor(task -> Schedulers.boundedElastic().schedule(task));
        container.setMaxSubscriptionRegistrationWaitingTime(REGISTRATION_TIMEOUT.toMillis());
    }

    RedisUserNotificationBus(StringRedisTemplate redis, ObjectMapper objectMapper,
            FinanceExRedisKeyBuilder keys, RedisMessageListenerContainer container) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.keys = keys;
        this.container = container;
    }

    private static ThreadPoolExecutor newLifecycleExecutor() {
        CustomizableThreadFactory factory = new CustomizableThreadFactory("finex-notification-lifecycle-");
        factory.setDaemon(true);
        // 最多四个注册任务及一个清理批次；ACK 和容器内部订阅不得使用该串行线程。
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_REGISTRATIONS + 1), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    private static void dispatchNotificationTask(Executor executor, Runnable task) {
        try {
            executor.execute(task);
        } catch (RuntimeException ex) {
            // Spring 在派发回调之后才完成内部订阅 Future，提交异常不能打断该确认链。
            // 丢弃 ACK 不等于确认成功：业务订阅仍须收到频道 ACK，否则按原期限失败。
            warn("user-notification.dispatch", ex);
        }
    }

    @PostConstruct
    public void start() {
        registrationLock.lock();
        try {
            if (closed) {
                return;
            }
            container.afterPropertiesSet();
            container.start();
        } catch (RuntimeException ex) {
            warn("user-notification.listener.start", ex);
        } finally {
            registrationLock.unlock();
        }
    }

    @Override
    public void publish(UserNotificationRecipient recipient, String frozenNotification) {
        redis.convertAndSend(keys.userNotificationChannel(recipient.tenantId(), recipient.userId()),
                frozenNotification);
    }

    @Override
    public Mono<Disposable> subscribe(UserNotificationRecipient recipient, Consumer<UserNotification> consumer) {
        return Mono.defer(() -> {
            if (closed || recipient == null || !recipient.valid() || consumer == null) {
                return Mono.error(new IllegalStateException("User notification subscription unavailable"));
            }
            schedulePendingCleanup();
            Registration registration = new Registration(recipient);
            Listener listener = listeners.compute(recipient, (key, current) -> {
                if (closed) {
                    throw new IllegalStateException("User notification bus stopped");
                }
                Listener selected = current == null ? new Listener(key) : current;
                selected.consumers.put(registration, consumer);
                return selected;
            });
            registration.listener = listener;
            if (listener.starting.compareAndSet(false, true)) {
                register(listener);
            }
            return listener.awaitReady()
                    .thenReturn((Disposable) registration)
                    .doFinally(signal -> {
                        if (signal == SignalType.CANCEL || signal == SignalType.ON_ERROR) {
                            registration.dispose();
                        }
                    })
                    .doOnDiscard(Disposable.class, Disposable::dispose);
        });
    }

    private void register(Listener listener) {
        if (!registrations.tryAcquire()) {
            listener.ready.tryEmitError(new IllegalStateException("User notification registration capacity exceeded"));
            return;
        }
        try {
            lifecycleExecutor.execute(() -> {
                try {
                    addListener(listener);
                    listener.addCompleted.set(true);
                    listener.confirmReady();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    listener.ready.tryEmitError(ex);
                } catch (Exception ex) {
                    listener.ready.tryEmitError(ex);
                } finally {
                    try {
                        // 取消只登记清理，add 退出后交给独立批次，不占公共线程等待退订。
                        if (listener.closed.get() && listener.adding.get()) {
                            requestCleanup(listener);
                        }
                    } finally {
                        registrations.release();
                    }
                }
            });
        } catch (RuntimeException ex) {
            registrations.release();
            listener.ready.tryEmitError(ex);
        }
    }

    private void addListener(Listener listener) throws InterruptedException, TimeoutException {
        long remaining = listener.remainingNanos();
        if (remaining <= 0 || !registrationLock.tryLock(remaining, TimeUnit.NANOSECONDS)) {
            throw new TimeoutException("User notification registration deadline exceeded");
        }
        try {
            if (closed || listener.closed.get()) {
                throw new IllegalStateException("User notification subscription closed");
            }
            if (listener.remainingNanos() <= 0) {
                throw new TimeoutException("User notification registration deadline exceeded");
            }
            listener.adding.set(true);
            container.addMessageListener(listener, listener.topic);
        } finally {
            registrationLock.unlock();
        }
    }

    private boolean remove(Listener listener) {
        registrationLock.lock();
        try {
            if (destroyed || listener.removed || !listener.adding.get()) {
                return true;
            }
            // Spring 先删本地映射再退订；部分失败后须恢复映射，否则重试 remove 可能空操作。
            // 原 Listener 已关闭，仅修复容器注册关系，不恢复消费者或就绪确认。
            if (listener.removalNeedsRepair) {
                container.addMessageListener(listener, listener.topic);
            }
            container.removeMessageListener(listener, listener.topic);
            listener.removalNeedsRepair = false;
            listener.removed = true;
            return true;
        } catch (RuntimeException ex) {
            listener.removalNeedsRepair = true;
            warn("user-notification.listener.remove", ex);
            return false;
        } finally {
            registrationLock.unlock();
        }
    }

    private void closeListener(Listener listener) {
        listener.closed.set(true);
        listener.ready.tryEmitError(new IllegalStateException("User notification subscription closed"));
        requestCleanup(listener);
    }

    private void requestCleanup(Listener listener) {
        synchronized (cleanupMonitor) {
            if (closed) {
                return;
            }
            if (listener.adding.get() && listener.cleanupScheduled.compareAndSet(false, true)) {
                pendingCleanup.add(listener);
            }
            schedulePendingCleanup();
        }
    }

    private void schedulePendingCleanup() {
        synchronized (cleanupMonitor) {
            if (closed) {
                return;
            }
            // 失败项仅在后续生命周期活动时重试，不由清理任务自身无限循环重试。
            pendingCleanup.addAll(failedCleanup);
            failedCleanup.clear();
            scheduleCleanupBatch();
        }
    }

    private void scheduleCleanupBatch() {
        if (closed || cleanupScheduled || pendingCleanup.isEmpty()) {
            return;
        }
        cleanupScheduled = true;
        try {
            lifecycleExecutor.execute(this::drainCleanup);
        } catch (RuntimeException ex) {
            // 待清理引用仍保留，下次生命周期活动可重试；禁止同步或公共线程池兜底。
            cleanupScheduled = false;
            warn("user-notification.listener.cleanup", ex);
        }
    }

    private void drainCleanup() {
        List<Listener> batch = new ArrayList<>(CLEANUP_BATCH_SIZE);
        synchronized (cleanupMonitor) {
            var iterator = pendingCleanup.iterator();
            while (!closed && iterator.hasNext() && batch.size() < CLEANUP_BATCH_SIZE) {
                batch.add(iterator.next());
                iterator.remove();
            }
        }
        List<Listener> failed = new ArrayList<>();
        try {
            for (Listener listener : batch) {
                if (!remove(listener)) {
                    failed.add(listener);
                }
            }
        } finally {
            synchronized (cleanupMonitor) {
                if (!closed) {
                    failedCleanup.addAll(failed);
                }
                cleanupScheduled = false;
                // 空队列判断与重新调度使用同一把锁，避免并发退出丢失清理唤醒。
                scheduleCleanupBatch();
            }
        }
    }

    @PreDestroy
    public void stop() {
        closed = true;
        listeners.values().forEach(listener -> {
            listener.closed.set(true);
            listener.consumers.clear();
            listener.ready.tryEmitError(new IllegalStateException("User notification bus stopped"));
        });
        listeners.clear();
        registrationLock.lock();
        try {
            if (!destroyed) {
                destroyed = true;
                container.destroy();
            }
        } catch (Exception ex) {
            warn("user-notification.listener.stop", ex);
        } finally {
            registrationLock.unlock();
            synchronized (cleanupMonitor) {
                pendingCleanup.clear();
                failedCleanup.clear();
            }
            // 不丢弃已排队注册，让其检查 closed 后退出并归还许可。
            lifecycleExecutor.shutdown();
        }
    }

    private static void warn(String operation, Exception ex) {
        log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                        "User notification transport degraded")
                .operation(operation).attribute("failureType", ex.getClass().getSimpleName()).build());
    }

    private final class Registration implements Disposable {
        private final UserNotificationRecipient recipient;
        private final AtomicBoolean disposed = new AtomicBoolean();
        private Listener listener;

        private Registration(UserNotificationRecipient recipient) {
            this.recipient = recipient;
        }

        @Override
        public void dispose() {
            if (!disposed.compareAndSet(false, true)) {
                return;
            }
            AtomicBoolean last = new AtomicBoolean();
            listeners.computeIfPresent(recipient, (key, current) -> {
                if (current != listener) {
                    return current;
                }
                current.consumers.remove(this);
                if (current.consumers.isEmpty()) {
                    current.closed.set(true);
                    last.set(true);
                    return null;
                }
                return current;
            });
            if (last.get()) {
                closeListener(listener);
            }
        }

        @Override
        public boolean isDisposed() {
            return disposed.get();
        }
    }

    private final class Listener implements MessageListener, SubscriptionListener {
        private final ChannelTopic topic;
        private final byte[] channel;
        private final long deadlineNanos = System.nanoTime() + REGISTRATION_TIMEOUT.toNanos();
        private final Map<Registration, Consumer<UserNotification>> consumers = new ConcurrentHashMap<>();
        private final Sinks.One<Void> ready = Sinks.one();
        private final AtomicBoolean starting = new AtomicBoolean();
        private final AtomicBoolean adding = new AtomicBoolean();
        private final AtomicBoolean addCompleted = new AtomicBoolean();
        private final AtomicBoolean channelAcknowledged = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicBoolean cleanupScheduled = new AtomicBoolean();
        // 仅在容器生命周期锁内访问，合并取消及注册 finally 的重复移除。
        private boolean removed;
        private boolean removalNeedsRepair;
        private volatile boolean confirmed;

        private Listener(UserNotificationRecipient recipient) {
            topic = new ChannelTopic(keys.userNotificationChannel(recipient.tenantId(), recipient.userId()));
            channel = topic.getTopic().getBytes(StandardCharsets.UTF_8);
        }

        private long remainingNanos() {
            return deadlineNanos - System.nanoTime();
        }

        private Mono<Void> awaitReady() {
            // 新连接复用已确认的共享监听时，不重新计算首次注册的过期期限。
            return confirmed ? ready.asMono()
                    : ready.asMono().timeout(Duration.ofNanos(Math.max(1L, remainingNanos())));
        }

        @Override
        public void onChannelSubscribed(byte[] subscribedChannel, long count) {
            if (!closed.get() && Arrays.equals(channel, subscribedChannel)) {
                channelAcknowledged.set(true);
                confirmReady();
            }
        }

        private void confirmReady() {
            // ACK 可能先于 add 返回；回调不取注册锁，也不阻塞 Redis 分发执行器。
            if (closed.get() || !addCompleted.get() || !channelAcknowledged.get()) {
                return;
            }
            if (remainingNanos() <= 0) {
                ready.tryEmitError(new TimeoutException("User notification registration deadline exceeded"));
                return;
            }
            confirmed = true;
            ready.tryEmitEmpty();
        }

        @Override
        public void onMessage(Message message, byte[] pattern) {
            if (closed.get() || message.getBody().length > UserNotification.MAX_SERIALIZED_BYTES
                    || !topic.getTopic().equals(new String(message.getChannel(), StandardCharsets.UTF_8))) {
                return;
            }
            try {
                UserNotification notification = objectMapper.readValue(message.getBody(), UserNotification.class);
                if (notification == null || notification.type() == null || notification.type().isBlank()
                        || notification.data() == null) {
                    return;
                }
                consumers.forEach((registration, consumer) -> {
                    if (!registration.isDisposed()) {
                        try {
                            consumer.accept(notification);
                        } catch (RuntimeException ex) {
                            warn("user-notification.deliver", ex);
                        }
                    }
                });
            } catch (Exception ex) {
                warn("user-notification.decode", ex);
            }
        }
    }
}
