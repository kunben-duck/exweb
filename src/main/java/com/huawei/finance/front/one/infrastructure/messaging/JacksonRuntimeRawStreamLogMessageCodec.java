package com.huawei.finance.front.one.infrastructure.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.conversation.RuntimeRawStreamLogMessageCodec;
import com.huawei.finance.front.one.domain.chat.RuntimeRawStreamChunk;
import org.springframework.stereotype.Component;

/**
 * 基于 Jackson 的 Runtime raw log MQ 消息编解码器。
 *
 * <p>该 codec 只序列化下游 Runtime 原始响应片段及其归属字段，不包含 Cookie、
 * Authorization、请求头或前端可见事件 payload。</p>
 */
@Component
public class JacksonRuntimeRawStreamLogMessageCodec implements RuntimeRawStreamLogMessageCodec {
    private final ObjectMapper objectMapper;

    public JacksonRuntimeRawStreamLogMessageCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String encode(RuntimeRawStreamChunk chunk) {
        try {
            return objectMapper.writeValueAsString(chunk);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("encode runtime raw stream chunk failed", ex);
        }
    }

    @Override
    public RuntimeRawStreamChunk decode(String payload) {
        try {
            return objectMapper.readValue(payload, RuntimeRawStreamChunk.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("decode runtime raw stream chunk failed", ex);
        }
    }
}
