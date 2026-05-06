package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.agent.AgentBinding;
import com.huawei.finance.front.one.domain.routing.RouteTarget;
import com.huawei.finance.front.one.domain.task.ContinuationDecision;
import com.huawei.finance.front.one.domain.task.TaskCard;

/**
 * active task 编排结果。
 *
 * @param decision ContinuationGuard 的最终续接决策。
 * @param route 本轮可直接执行的路由；routeNew 为 true 时该字段为空。
 * @param binding 本轮要使用的绑定。
 * @param taskCard 本轮要使用或已更新的任务卡片。
 * @param routeNew true 表示当前任务已挂起或释放，本轮需要重新走主路由。
 */
public record ActiveTaskResolution(
        ContinuationDecision decision,
        RouteTarget route,
        AgentBinding binding,
        TaskCard taskCard,
        boolean routeNew
) {
    /**
     * 表示 active task 已处理完毕，本轮需要重新路由。
     *
     * @param decision 触发重新路由的决策。
     * @return active task 编排结果。
     */
    public static ActiveTaskResolution routeNew(ContinuationDecision decision) {
        return new ActiveTaskResolution(decision, null, null, null, true);
    }
}
