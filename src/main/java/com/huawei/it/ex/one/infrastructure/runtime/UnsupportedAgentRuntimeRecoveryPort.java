/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.runtime;

import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRecoveryPort;
import com.huawei.it.ex.one.application.integration.agent.AgentRuntimeRecoveryRequest;
import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;

/**
 * 默认 Runtime 恢复端口实现。
 *
 * <p>当前正式版本不假设 Relay Runtime 具备可靠断点恢复能力，因此默认不支持接管续跑。
 * 如果后续 Runtime 提供了明确的 resume token 和幂等输出保证，可新增实现替换该 bean。</p>
 */
public class UnsupportedAgentRuntimeRecoveryPort implements AgentRuntimeRecoveryPort {
    @Override
    public boolean supports(AgentRuntimeRecoveryRequest request) {
        return false;
    }

    @Override
    public Flux<ChatEvent> recover(AgentRuntimeRecoveryRequest request) {
        return Flux.error(new UnsupportedOperationException("当前 AgentRuntime 不支持可靠断点接管"));
    }
}
