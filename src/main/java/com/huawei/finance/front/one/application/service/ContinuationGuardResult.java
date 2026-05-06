package com.huawei.finance.front.one.application.service;

import com.huawei.finance.front.one.domain.task.ContinuationDecision;

/**
 * ContinuationGuard 对 active task 的续接判断结果。
 *
 * @param decision 本轮用户输入与当前任务的关系。
 * @param reason 机器可读的判断原因，用于任务事件和排障。
 * @param confirmationQuestion 需要用户澄清时展示的问题。
 */
public record ContinuationGuardResult(
        ContinuationDecision decision,
        String reason,
        String confirmationQuestion
) {
    /**
     * 创建无需澄清的问题。
     *
     * @param decision 决策类型。
     * @param reason 判断原因。
     * @return 续接判断结果。
     */
    public static ContinuationGuardResult of(ContinuationDecision decision, String reason) {
        return new ContinuationGuardResult(decision, reason, null);
    }
}
