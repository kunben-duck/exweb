package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRunStartResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
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
     * {@code POST /api/v1/ex/chat/runs}。</p>
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param command 聊天命令，包含会话、用户输入、附件和 metadata；身份由应用层回填。
     * @param forwardHeaders 请求入口捕获的转发头快照；只允许进入可信下游 adapter。
     * @return 本轮 run 的标准聊天事件流。
     */
    Flux<ChatEvent> executeRun(UserContext user, ChatCommand command, RuntimeForwardHeaders forwardHeaders);

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
