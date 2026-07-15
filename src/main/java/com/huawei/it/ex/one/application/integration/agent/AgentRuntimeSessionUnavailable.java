package com.huawei.it.ex.one.application.integration.agent;

/**
 * 标记下游明确确认 Runtime session 已不存在或损坏。
 *
 * <p>该标记只用于让应用层永久失效对应 RuntimeBinding；普通网络或协议异常不能实现此接口。</p>
 */
public interface AgentRuntimeSessionUnavailable {
}
