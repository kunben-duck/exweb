package com.huawei.it.ex.one.intent.application.model;

import com.huawei.it.ex.one.common.event.ChatEvent;

/**
 * 路由阶段输出帧：要么是前端可见事件/进度，要么是最终路由结果。
 */
public record RouteSignalFrame(
        RouteSignalProgress progress,
        RouteSignalResult result,
        ChatEvent event
) {
    public static RouteSignalFrame progress(RouteSignalProgress progress) {
        return new RouteSignalFrame(progress, null, null);
    }

    public static RouteSignalFrame result(RouteSignalResult result) {
        return new RouteSignalFrame(null, result, null);
    }

    public static RouteSignalFrame event(ChatEvent event) {
        return new RouteSignalFrame(null, null, event);
    }

    public boolean progressFrame() {
        return progress != null;
    }

    public boolean resultFrame() {
        return result != null;
    }

    public boolean eventFrame() {
        return event != null;
    }
}
