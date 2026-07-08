package com.huawei.finance.front.one.application.service.runtime;

import java.util.Map;

/**
 * 创建 RuntimeBinding 的内部命令。
 *
 * <p>绑定创建涉及归属、provider、leaf 和下游会话 ID。使用命令对象能避免长参数列表，
 * 也让 DomainAgent 和 Relay 共用同一条创建路径。</p>
 */
record RuntimeBindingCreateCommand(
        String tenantId,
        String userId,
        String sessionId,
        String provider,
        String runId,
        String leafMessageId,
        String runtimeSessionId,
        Map<String, Object> metadata
) {
    RuntimeBindingCreateCommand {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
