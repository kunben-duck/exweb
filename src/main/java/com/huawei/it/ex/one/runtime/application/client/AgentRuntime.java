package com.huawei.it.ex.one.runtime.application.client;

import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRequest;
import com.huawei.it.ex.one.common.event.ChatEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 复杂任务 AgentRuntime 防腐层。
 *
 * <p>SuperAgent 主控服务只依赖这个应用层端口提交 Runtime 查询，不直接依赖任何具体 Runtime
 * 的 SDK、HTTP 协议或内部会话模型。多个 Runtime provider 可以同时注册，应用层按
 * RuntimeBinding.provider 或默认 provider 动态选择。</p>
 */
public interface AgentRuntime {
    /**
     * Runtime provider 稳定编码，例如 relay、domain-agent。
     *
     * @return provider 编码。
     */
    default String provider() {
        return "relay";
    }

    /**
     * 向当前装配的 AgentRuntime 提交一轮查询。
     *
     * @param request Runtime 查询请求。
     * @return 标准聊天事件流。
     */
    Flux<ChatEvent> query(AgentRuntimeRequest request);

    /**
     * 尽力取消已提交到 Runtime 的 run。
     *
     * <p>取消失败不得影响本服务本地 run.cancelled 事件；实现方应把错误吞掉或转成空 Mono。</p>
     *
     * @param request Runtime 取消请求。
     * @return 完成信号。
     */
    Mono<Void> cancel(AgentRuntimeCancelRequest request);
}
