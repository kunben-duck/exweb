package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 使用服务统一Jackson配置估算标准事件的UTF-8序列化大小。 */
@Component
public class RuntimeEventSizeEstimator {
    private final ObjectMapper objectMapper;

    public RuntimeEventSizeEstimator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public long bytes(ChatEvent event) {
        if (event == null) {
            return 0L;
        }
        Map<String, Object> wireEvent = new LinkedHashMap<>();
        wireEvent.put("runId", event.runId());
        wireEvent.put("sessionId", event.sessionId());
        // Runtime阶段尚未分配数据库序号，使用long最大值按最终wire格式保守计费。
        wireEvent.put("sequence", Long.MAX_VALUE);
        wireEvent.put("type", event.type());
        wireEvent.put("createdAt", event.createdAt() == null ? null : event.createdAt().toString());
        wireEvent.put("payload", event.payload() == null ? Map.of() : event.payload());
        try {
            return objectMapper.writeValueAsBytes(wireEvent).length;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Runtime事件序列化大小计算失败", ex);
        }
    }
}
