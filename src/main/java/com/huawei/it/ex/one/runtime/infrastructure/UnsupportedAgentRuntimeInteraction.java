package com.huawei.it.ex.one.runtime.infrastructure;

import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.runtime.application.client.AgentRuntimeInteraction;
import com.huawei.it.ex.one.common.event.ChatEvent;
import reactor.core.publisher.Flux;

/**
 * 默认 Runtime 交互续接实现。
 *
 * <p>不是所有 Runtime adapter 都支持澄清、审批、确认等等待用户输入后的续接。默认实现显式返回
 * 不支持，避免系统启动时强依赖某个具体 Runtime。</p>
 */
public class UnsupportedAgentRuntimeInteraction implements AgentRuntimeInteraction {
    @Override
    public boolean supportsWaitingUserResponse(String runtimeProvider) {
        return false;
    }

    @Override
    public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
        return Flux.error(new UnsupportedOperationException("当前 AgentRuntime 不支持交互续接"));
    }
}
