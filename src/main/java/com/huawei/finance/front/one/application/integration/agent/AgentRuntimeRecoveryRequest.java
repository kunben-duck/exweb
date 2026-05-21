package com.huawei.finance.front.one.application.integration.agent;

import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.domain.chat.ChatRunExecution;

/**
 * Runtime 接管恢复请求。
 *
 * @param run 需要恢复的业务 run。
 * @param execution 已被当前实例抢占到 RECOVERING 的执行控制面快照。
 * @param runtimeResumeToken Runtime 自身提供的断点恢复 token。
 * @param lastPersistedSeq 当前 run 已落库的最大事件 seq。
 */
public record AgentRuntimeRecoveryRequest(
        ChatRun run,
        ChatRunExecution execution,
        String runtimeResumeToken,
        long lastPersistedSeq
) {
}
