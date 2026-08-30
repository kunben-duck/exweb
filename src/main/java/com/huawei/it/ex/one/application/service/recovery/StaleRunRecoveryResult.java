/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.service.recovery;

/**
 * stale run 恢复结果。
 *
 * @param recovered 是否已成功完成恢复动作。
 * @param strategy 实际执行的策略名。
 * @param message 结果说明，用于日志和诊断。
 */
public record StaleRunRecoveryResult(
        boolean recovered,
        String strategy,
        String message
) {
    public static StaleRunRecoveryResult recovered(String strategy, String message) {
        return new StaleRunRecoveryResult(true, strategy, message);
    }

    public static StaleRunRecoveryResult skipped(String strategy, String message) {
        return new StaleRunRecoveryResult(false, strategy, message);
    }
}
