package com.huawei.finance.front.one.application.integration.conversation;

import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;

/**
 * Runtime 原始响应日志发布端口。
 *
 * <p>该端口位于 ChatService 主链路和企业 MQ 之间。实现必须采用 best-effort 语义：
 * 发布失败、超时或 MQ 不可用都不能影响 Relay normalizer、ChatEvent 入库、WebSocket 推送
 * 或 run 生命周期。</p>
 */
public interface RuntimeRawStreamLogPublisher {
    /**
     * 发布一段原始 Runtime chunk。
     *
     * @param chunk 原始响应片段，不包含 Cookie、Authorization 或其他请求头。
     */
    void publish(RuntimeRawStreamChunk chunk);
}
