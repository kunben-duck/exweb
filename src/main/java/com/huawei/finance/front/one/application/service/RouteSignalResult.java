package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.routing.RouteTarget;

/**
 * 首轮路由信号解析结果。
 *
 * <p>RouteTarget 描述本轮最终处理路径；IntentDecision 只在实际调用过意图服务时存在，
 * 用于继续传递给 AgentRuntime 或系统响应执行器。</p>
 *
 * @param route 本轮最终路由结果，不能为空。
 * @param intentDecision 意图服务识别结果；未调用意图服务时为空。
 */
public record RouteSignalResult(
        RouteTarget route,
        IntentDecision intentDecision
) {
    /**
     * 创建不包含意图识别结果的路由信号结果。
     *
     * @param route 本轮最终路由结果。
     * @return 路由信号结果。
     */
    public static RouteSignalResult of(RouteTarget route) {
        return new RouteSignalResult(route, null);
    }
}
