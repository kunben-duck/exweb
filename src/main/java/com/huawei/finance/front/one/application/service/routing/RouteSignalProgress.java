package com.huawei.finance.front.one.application.service.routing;

import java.util.Map;

/**
 * 外部路由阶段进度。
 *
 * <p>该对象只描述阶段和简短展示文案，不携带 RouteMemory history、prompt 或意图原始响应。</p>
 */
public record RouteSignalProgress(
        String stage,
        String message,
        Map<String, Object> attributes
) {
    public RouteSignalProgress {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public static RouteSignalProgress of(String stage, String message, Map<String, Object> attributes) {
        return new RouteSignalProgress(stage, message, attributes);
    }
}
