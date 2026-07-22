package com.huawei.it.ex.one.application.service.chat;

import com.huawei.it.ex.one.application.config.ChatStreamProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;

import org.springframework.stereotype.Service;

/**
 * 流式事件合并扩展点。
 *
 * <p>早期版本在这里使用 push 型 {@code Flux.create} 合并连续 {@code message.delta}。
 * 在事件落库切换到专用 IO 调度器后，该实现会在下游消费慢时触发 Reactor overflow，并误把
 * ChatService 自身背压转换成 {@code run.failed}。生产止血版本改为原样透传：下游 Runtime
 * 输出的标准事件按原粒度落库和发布，避免 ChatService 内部降压逻辑中断用户任务。</p>
 *
 * <p>后续如果要重新启用合并，必须实现真正 demand-aware 的合并器，并通过高频 chunk、慢 DB、
 * 慢 WebSocket 等压测；不能再使用会主动 push 且以 overflow 终止任务的实现。</p>
 */
@Service
public class ChatDeltaCoalescer {
    /**
     * 保留配置构造参数，避免当前 Spring bean 装配和测试辅助构造大范围变化。
     *
     * @param properties 历史 delta 合并配置；当前生产止血版本不读取该配置。
     */
    public ChatDeltaCoalescer(ChatStreamProperties properties) {
    }

    /**
     * 原样返回事件流。
     *
     * @param source 原始事件流。
     * @return 未合并的原始事件流。
     */
    public Flux<ChatEvent> coalesce(Flux<ChatEvent> source) {
        return source;
    }
}
