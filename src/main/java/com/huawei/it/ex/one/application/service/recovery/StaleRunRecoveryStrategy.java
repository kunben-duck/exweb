/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.recovery;

/**
 * stale run 恢复策略。
 *
 * <p>策略负责具体恢复动作，例如失败闭合、人工确认或 Runtime 接管。策略实现不负责定时巡检；
 * 巡检与容量治理由 orchestrator/scheduler 分层处理。</p>
 */
public interface StaleRunRecoveryStrategy {
    /**
     * @return 策略名，例如 MANUAL_CONFIRMATION、FAIL_FAST、RUNTIME_TAKEOVER。
     */
    String strategyName();

    /**
     * 判断当前策略是否支持该 stale run。
     *
     * @param context 恢复上下文。
     * @return true 表示可尝试该策略。
     */
    boolean supports(StaleRunRecoveryContext context);

    /**
     * 执行恢复策略。
     *
     * @param context 恢复上下文。
     * @return 恢复结果。
     */
    StaleRunRecoveryResult recover(StaleRunRecoveryContext context);
}
