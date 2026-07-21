package com.huawei.it.ex.one.runtime.application.model;

/**
 * Runtime 接管恢复请求。
 *
 * @param run 需要恢复的业务 run。
 * @param execution 已被当前实例抢占到 RECOVERING 的执行控制面快照。
 * @param runtimeResumeToken Runtime 自身提供的断点恢复 token。
 * @param lastPersistedSeq 当前 run 已落库的最大事件 seq。
 */
public record AgentRuntimeRecoveryRequest(
        RuntimeRunSnapshot run,
        RuntimeExecutionSnapshot execution,
        String runtimeResumeToken,
        long lastPersistedSeq
) {
}
