package com.huawei.it.ex.one.application.service.runtime;

import com.huawei.it.ex.one.application.config.RuntimeStreamLimitsProperties;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;

import org.springframework.stereotype.Component;

import java.time.Duration;

/** 创建共享实例预算约束下的Runtime事件桥接。 */
@Component
public class RuntimePendingEventBridgeFactory {
    private final RuntimeStreamLimitsProperties properties;
    private final RuntimePendingEventGuard eventGuard;

    public RuntimePendingEventBridgeFactory(RuntimeStreamLimitsProperties properties,
                                            RuntimePendingEventGuard eventGuard) {
        this.properties = properties;
        this.eventGuard = eventGuard;
    }

    public RuntimePendingEventBridge create(String runId) {
        return new RuntimePendingEventBridge(runId, properties.getPendingMaxEventsPerRun(), eventGuard);
    }

    public Flux<ChatEvent> guard(String runId, Flux<ChatEvent> source) {
        return eventGuard.guard(runId, source);
    }

    public Duration overflowCancelTimeout() {
        return properties.getOverflowCancelTimeout();
    }

    /** 仅供不启动Spring上下文的adapter单元测试保持原构造方式。 */
    public static RuntimePendingEventBridgeFactory defaults() {
        RuntimeStreamLimitsProperties properties = new RuntimeStreamLimitsProperties();
        RuntimePendingBudgetRegistry registry = new RuntimePendingBudgetRegistry(properties);
        RuntimePendingEventGuard guard = new RuntimePendingEventGuard(
                registry, new RuntimeEventSizeEstimator(new ObjectMapper()));
        return new RuntimePendingEventBridgeFactory(properties, guard);
    }
}
