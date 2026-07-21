package com.huawei.it.ex.one.application.integration.agent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 服务端 Agent 模式转换产生的可信下游参数。 */
public record AgentModeOutboundParameters(
        Map<String, Object> requestMetadata,
        Map<String, Object> relayConfig
) {
    public AgentModeOutboundParameters {
        requestMetadata = requestMetadata == null ? Map.of() : Map.copyOf(requestMetadata);
        relayConfig = relayConfig == null ? Map.of() : Map.copyOf(relayConfig);
    }

    public static AgentModeOutboundParameters empty() {
        return new AgentModeOutboundParameters(Map.of(), Map.of());
    }

    /** 服务端转换结果后写入并覆盖同名客户端 metadata。 */
    public Map<String, Object> mergeRequestMetadata(Map<String, Object> metadata) {
        if (requestMetadata.isEmpty()) {
            return metadata == null ? Map.of() : metadata;
        }
        Map<String, Object> merged = metadata == null || metadata.isEmpty()
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(metadata);
        merged.putAll(requestMetadata);
        return Map.copyOf(merged);
    }
}
