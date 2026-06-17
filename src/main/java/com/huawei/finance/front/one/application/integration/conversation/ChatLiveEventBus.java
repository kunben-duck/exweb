package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.ChatEvent;
import reactor.core.publisher.Flux;

/**
 * 跨实例聊天实时事件总线端口。
 *
 * <p>数据库仍是事件事实源；该端口只负责把已经落库的 run topic 事件分发到其他应用实例，
 * 让 WebSocket 连接不依赖网关粘性路由。</p>
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
     * 订阅当前 JVM 收到的远端 topic 事件。
     *
     * @param topicId run 级 stream topic。
     * @return 远端实例经事件总线转发过来的实时事件流。
     */
    Flux<ChatEvent> subscribe(String topicId);
}
