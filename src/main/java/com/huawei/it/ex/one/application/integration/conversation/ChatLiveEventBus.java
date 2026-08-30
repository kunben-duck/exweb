/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.conversation;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;

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
}
