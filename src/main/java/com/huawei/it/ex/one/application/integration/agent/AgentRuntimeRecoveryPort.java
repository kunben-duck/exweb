/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.application.integration.agent;

import com.huawei.it.ex.one.domain.chat.ChatEvent;

import reactor.core.publisher.Flux;

/**
 * AgentRuntime 断点接管恢复端口。
 *
 * <p>当前 Relay WebSocket adapter 不支持可靠接管同一个运行中 run，因此 {@link #supports(AgentRuntimeRecoveryRequest)}
 * 返回 false。未来 Runtime 若能基于 runtimeSessionId、resumeToken 和 lastSeq 保证不重复输出，
 * 可替换该端口实现真正续接同一个 run。</p>
 */
public interface AgentRuntimeRecoveryPort {
    /**
     * 判断当前 Runtime 是否支持该 run 的可靠恢复。
     *
     * @param request 恢复请求。
     * @return true 表示可安全接管。
     */
    boolean supports(AgentRuntimeRecoveryRequest request);

    /**
     * 从 Runtime 断点继续输出同一个 run。
     *
     * @param request 恢复请求。
     * @return 恢复后的事件流。
     */
    Flux<ChatEvent> recover(AgentRuntimeRecoveryRequest request);
}
