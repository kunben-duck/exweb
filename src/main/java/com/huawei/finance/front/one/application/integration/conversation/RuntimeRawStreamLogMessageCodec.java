package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;

/**
 * Runtime raw log MQ 消息编解码端口。
 *
 * <p>企业私有 MQ 或其他消息队列都应复用同一业务消息结构；替换 MQ 时只替换发送
 * 和消费 adapter，不改变 raw chunk 的语义字段。</p>
 */
public interface RuntimeRawStreamLogMessageCodec {
    /**
     * 将 raw chunk 编码为 MQ 消息体。
     *
     * @param chunk 原始响应片段。
     * @return MQ 消息体字符串。
     */
    String encode(RuntimeRawStreamChunk chunk);

    /**
     * 将 MQ 消息体解码为 raw chunk。
     *
     * @param payload MQ 消息体字符串。
     * @return 原始响应片段。
     */
    RuntimeRawStreamChunk decode(String payload);
}
