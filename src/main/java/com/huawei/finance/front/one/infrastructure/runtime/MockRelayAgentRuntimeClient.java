package com.huawei.finance.front.one.infrastructure.runtime;

import com.huawei.finance.front.one.application.gateway.AgentRuntimeClient;
import com.huawei.finance.front.one.application.gateway.RuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.routing.RuntimeProtocol;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * Relay Runtime mock 实现。
 *
 * <p>未启用真实 Runtime 时使用，保证复杂任务链路在本地开发环境也能跑通。</p>
 */
@Component
@ConditionalOnProperty(name = "financeex.runtime.enabled", havingValue = "false", matchIfMissing = true)
public class MockRelayAgentRuntimeClient implements AgentRuntimeClient {
    @Override public RuntimeProtocol protocol() { return RuntimeProtocol.HTTP_STREAM; }
    @Override public boolean supports(RuntimeProtocol protocol) { return protocol == RuntimeProtocol.HTTP_STREAM; }
    @Override public Flux<ChatEvent> stream(RuntimeRequest request) {
        if (responseMode(request) == ChatResponseMode.BLOCK) {
            return Flux.just(
                    MessageDeltaEvent.of(request.runId(), request.sessionId(), "复杂任务已下发 Relay Agent / Python Runtime。当前为 mock runtime 响应，可通过 financeex.runtime.enabled=true 接入真实 Runtime。"),
                    MessageCompletedEvent.of(request.runId(), request.sessionId())
            );
        }
        // 返回固定事件，帮助前端联调复杂任务的流式展示。
        return Flux.just(
                MessageDeltaEvent.of(request.runId(), request.sessionId(), "复杂任务已下发 Relay Agent / Python Runtime。"),
                MessageDeltaEvent.of(request.runId(), request.sessionId(), "当前为 mock runtime 响应，可通过 financeex.runtime.enabled=true 接入真实 Runtime。"),
                MessageCompletedEvent.of(request.runId(), request.sessionId())
        );
    }

    private ChatResponseMode responseMode(RuntimeRequest request) {
        return request.responseMode() == null ? ChatResponseMode.BLOCK : request.responseMode();
    }
}
