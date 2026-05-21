package com.huawei.finance.front.one.application.integration.identity;

/**
 * 当前应用运行实例标识提供者。
 *
 * <p>实例 ID 属于运行控制面信息，用于 run 租约、心跳、恢复抢占和排障，不进入用户业务主表。
 * 应用层只依赖该端口；后续如果要从注册中心、Kubernetes metadata 或企业服务治理框架获取实例 ID，
 * 只需要替换该接口实现。</p>
 */
public interface ApplicationInstanceIdProvider {
    /**
     * 返回当前进程的运行实例 ID。
     *
     * <p>实现必须在进程存活期间保持稳定；不同进程同时启动时应尽量保证不冲突。</p>
     *
     * @return 当前应用实例运行 ID。
     */
    String currentInstanceId();
}
