package com.huawei.it.ex.one.application.facade;

import com.huawei.it.ex.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.domain.chat.ChatCommand;
import com.huawei.it.ex.one.domain.chat.ChatEvent;
import com.huawei.it.ex.one.domain.chat.ChatRunStartResult;
import com.huawei.it.ex.one.domain.chat.ChatRunStopResult;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 财经聊天应用门面。
 */
public interface FinanceChatFacade {
    /**
     * 执行一轮聊天 run 并产生标准事件流。
     *
     * <p>该方法供 {@code startRun} 在后台执行 run 使用，对外提问入口只暴露
     * {@code POST /v1/chat/runs}。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param command 聊天命令，包含会话、用户输入、附件和 metadata；身份由应用层回填。
     * @param forwardHeaders 请求入口捕获的转发头快照；只允许进入可信下游 adapter。
     * @return 本轮 run 的标准聊天事件流。
     */
    Flux<ChatEvent> executeRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders);

    /**
     * 使用入口捕获的链路追踪快照执行聊天 run。
     *
     * <p>默认实现保持内部调用方兼容；正式 HTTP 入口调用该重载，具体实现负责把 trace 上下文传到
     * 可能被路由到的 Relay，不得在异步线程重新解析 Provider。</p>
     */
    default Flux<ChatEvent> executeRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                       RuntimeForwardHeaders forwardHeaders) {
        return executeRun(user, command, forwardHeaders);
    }

    /**
     * 兼容内部测试和非 HTTP 调用方的默认执行入口，不携带请求头透传。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param command 聊天命令。
     * @return 本轮 run 的标准聊天事件流。
     */
    default Flux<ChatEvent> executeRun(UserContext user, ChatCommand command) {
        return executeRun(user, command, RuntimeForwardHeaders.empty());
    }

    /**
     * 后台启动一轮聊天运行，并返回前端订阅所需的 run 创建结果。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param command 聊天命令，包含会话、用户输入、附件和 metadata。
     * @param forwardHeaders 请求入口捕获的 Runtime 转发头快照；不会持久化。
     * @return 后台 run 的创建结果。
     */
    Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders);

    /**
     * 使用入口捕获的链路追踪快照启动后台 run。
     */
    default Mono<ChatRunStartResult> startRun(UserContext user, TraceContext traceContext, ChatCommand command,
                                              RuntimeForwardHeaders forwardHeaders) {
        return startRun(user, command, forwardHeaders);
    }

    /**
     * 后台启动一轮聊天运行，不携带请求头透传。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param command 聊天命令。
     * @return 后台 run 的创建结果。
     */
    default Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command) {
        return startRun(user, command, RuntimeForwardHeaders.empty());
    }

    /**
     * 停止指定 run 的当前回答。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param runId run 标识。
     * @param forwardHeaders stop 请求入口捕获的转发头快照；用于可信下游 cancel。
     * @return stop 后的 run 状态。
     */
    Mono<ChatRunStopResult> stopRun(UserContext user, String runId, RuntimeForwardHeaders forwardHeaders);

    /**
     * 使用 stop 请求入口捕获的链路追踪快照取消 run。
     */
    default Mono<ChatRunStopResult> stopRun(UserContext user, TraceContext traceContext, String runId,
                                            RuntimeForwardHeaders forwardHeaders) {
        return stopRun(user, runId, forwardHeaders);
    }

    /**
     * 停止指定 run，不携带请求头透传。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param runId run 标识。
     * @return stop 后的 run 状态。
     */
    default Mono<ChatRunStopResult> stopRun(UserContext user, String runId) {
        return stopRun(user, runId, RuntimeForwardHeaders.empty());
    }
}
