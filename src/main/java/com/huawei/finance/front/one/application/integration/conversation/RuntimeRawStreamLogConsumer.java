package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;

/**
 * Runtime 原始响应日志消费端口。
 *
 * <p>不同 MQ 的 listener 只负责把消息解码为 {@link RuntimeRawStreamChunk}，再交给该端口。
 * 合并、脱敏、分片和落库属于应用侧消费逻辑，避免这些规则散落到具体 MQ SDK 适配器中。</p>
 */
public interface RuntimeRawStreamLogConsumer {
    /**
     * 消费一段 raw chunk。
     *
     * @param chunk MQ 中收到的 Runtime 原始响应片段。
     */
    void consume(RuntimeRawStreamChunk chunk);
}
