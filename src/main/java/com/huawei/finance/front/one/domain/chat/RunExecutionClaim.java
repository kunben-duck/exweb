package com.huawei.finance.front.one.domain.chat;

/**
 * 后台执行流持有的 run 写入权声明。
 *
 * <p>后台执行流写入 run 事件前都要使用该 claim 校验 execution 表中的 owner 和 fencing token。
 * 当 watchdog 或接管策略递增 token 后，旧执行流的迟到 delta/completed 会被拒绝。</p>
 *
 * @param runId 本轮 run 标识。
 * @param ownerInstanceId 启动该执行流的实例 ID。
 * @param fencingToken 执行流启动时获得的写入栅栏令牌。
 */
public record RunExecutionClaim(
        String runId,
        String ownerInstanceId,
        long fencingToken
) {
}
