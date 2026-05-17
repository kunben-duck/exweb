package com.huawei.finance.front.one.application.facade;

import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatRunStartResult;
import com.huawei.finance.front.one.domain.chat.ChatRunStopResult;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

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
     * @return 本轮 run 的标准聊天事件流。
     */
    Flux<ChatEvent> executeRun(UserContext user, ChatCommand command);

    /**
     * 后台启动一轮聊天运行，并返回前端订阅所需的 run 创建结果。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param command 聊天命令，包含会话、用户输入、附件和 metadata。
     * @return 后台 run 的创建结果。
     */
    Mono<ChatRunStartResult> startRun(UserContext user, ChatCommand command);

    /**
     * 基于已有 run 所属会话重新生成回答。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param runId 被重试的原 run 标识。
     * @param command 本次重试的前端命令；message 为空时由应用层复用最近用户消息。
     * @return 新 run 的创建结果。
     */
    Mono<ChatRunStartResult> retryRun(UserContext user, String runId, ChatCommand command);

    /**
     * 停止指定 run 的当前回答。
     *
     * @param user 请求入口解析出的不可变用户身份快照。
     * @param runId run 标识。
     * @return stop 后的 run 状态。
     */
    Mono<ChatRunStopResult> stopRun(UserContext user, String runId);
}
