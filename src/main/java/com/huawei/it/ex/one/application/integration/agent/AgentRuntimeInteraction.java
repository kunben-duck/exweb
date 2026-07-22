package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;

/**
 * AgentRuntime 运行中人机交互续接能力。
 *
 * <p>{@link AgentRuntime} 只表达普通问答和取消；澄清、审批、确认等协议级等待用户输入后的续接，
 * 统一收敛到该接口，避免把可选交互能力塞回 Runtime 主接口。</p>
 */
public interface AgentRuntimeInteraction {
    /**
     * 判断当前 Runtime provider 是否支持等待用户输入后的续接。
     *
     * <p>应用层用该能力判断决定是否进入 WAITING_USER。默认不支持，防止普通 adapter 保存出无法续接的
     * 等待态。</p>
     *
     * @param runtimeProvider Runtime provider 编码。
     * @return true 表示可创建等待用户输入状态。
     */
    default boolean supportsWaitingUserResponse(String runtimeProvider) {
        return false;
    }

    /**
     * 向当前装配的 AgentRuntime 提交等待用户输入后的续接响应。
     *
     * @param request Runtime Interaction 续接请求。
     * @return 标准聊天事件流。
     */
    Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request);
}
