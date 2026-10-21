/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.conversation;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 跨实例聊天实时事件总线端口。
 *
 * <p>数据库仍是持久化事件事实源；该端口负责把带全局 sequence 的 run topic 事件分发到其他应用实例，
 * 让 WebSocket 连接不依赖网关粘性路由。留存策略允许部分业务事件只实时传输。</p>
 */
public interface ChatLiveEventBus {
    /**
     * 发布一个已经落库的事件到 run 级 stream topic。
     *
     * @param topicId run 级 stream topic。
     * @param event 已持久化并带 seq 的事件。
     */
    void publish(String topicId, ChatEvent event);

    /**
     * 发布无法通过 Event Resume 补发的实时业务事件。
     *
     * <p>默认委托普通发布以保持测试替身和自定义实现兼容。可靠总线实现应覆盖该方法，避免发布失败时
     * 错误提示客户端可以从数据库恢复。</p>
     */
    default void publishLiveOnly(String topicId, ChatEvent event) {
        publish(topicId, event);
    }

    /**
     * 订阅当前 JVM 收到的远端 topic 事件。
     *
     * @param topicId run 级 stream topic。
     * @return 远端实例经事件总线转发过来的实时事件流。
     */
    Flux<ChatEvent> subscribe(String topicId);

    /**
     * 先接入 events 的有界缓冲，再等待 ready，最后读取数据库历史。
     * 自定义同步总线沿用原订阅行为；异步注册实现必须覆盖就绪确认。
     */
    default Subscription openSubscription(String topicId) {
        return new Subscription(subscribe(topicId), Mono.empty(), () -> { });
    }

    /** 内部订阅资源；调用方在成功、失败和取消时均须释放。 */
    record Subscription(Flux<ChatEvent> events, Mono<Void> ready, Disposable cleanup) implements Disposable {
        @Override
        public void dispose() {
            cleanup.dispose();
        }
    }
}
