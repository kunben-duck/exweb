package com.huawei.it.ex.one.application.integration.trace;

import com.huawei.it.ex.one.common.trace.TraceContext;

/**
 * 请求入口访问企业链路追踪框架的防腐层。
 *
 * <p>Provider 只能在 Servlet 请求入口调用。应用服务、异步任务和 Runtime adapter 必须接收入口
 * 已捕获的 {@link TraceContext}，不得再次读取 Jalor 等基于线程上下文的企业框架。</p>
 */
public interface TraceContextProvider {
    /**
     * 获取当前请求线程的链路追踪上下文。
     *
     * @return 当前追踪上下文；没有有效 traceId 时返回空上下文。
     */
    TraceContext resolve();
}
