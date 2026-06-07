package com.huawei.finance.front.one.infrastructure.messaging;

import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogPublisher;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;

/**
 * Runtime raw log 的空发布实现。
 *
 * <p>当 raw log 被关闭或企业框架尚未提供 MQ 实现时，该实现保证诊断
 * 旁路不会影响主链路。后续企业 MQ 接入只需提供新的 {@link RuntimeRawStreamLogPublisher}
 * bean 覆盖该默认实现。</p>
 */
public class NoopRuntimeRawStreamLogPublisher implements RuntimeRawStreamLogPublisher {
    @Override
    public void publish(RuntimeRawStreamChunk chunk) {
        // Intentionally no-op: raw log is diagnostic only.
    }
}
