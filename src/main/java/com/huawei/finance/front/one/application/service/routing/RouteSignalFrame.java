package com.huawei.finance.front.one.application.service.routing;

/**
 * 路由阶段输出帧：要么是前端可见进度，要么是最终路由结果。
 */
public record RouteSignalFrame(
        RouteSignalProgress progress,
        RouteSignalResult result
) {
    public static RouteSignalFrame progress(RouteSignalProgress progress) {
        return new RouteSignalFrame(progress, null);
    }

    public static RouteSignalFrame result(RouteSignalResult result) {
        return new RouteSignalFrame(null, result);
    }

    public boolean progressFrame() {
        return progress != null;
    }

    public boolean resultFrame() {
        return result != null;
    }
}
