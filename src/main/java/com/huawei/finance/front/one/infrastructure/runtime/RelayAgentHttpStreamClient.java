package com.huawei.finance.front.one.infrastructure.runtime;

import com.huawei.finance.front.one.application.gateway.AgentRuntimeClient;
import com.huawei.finance.front.one.application.gateway.RuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.ChatResponseMode;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import com.huawei.finance.front.one.domain.chat.MessageDeltaEvent;
import com.huawei.finance.front.one.domain.routing.RuntimeProtocol;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * Relay Agent HTTP Stream 实现。
 *
 * <p>启用 financeex.runtime.enabled=true 后，把复杂任务转发到外部 Runtime。</p>
 */
@Component
@ConditionalOnProperty(name = "financeex.runtime.enabled", havingValue = "true")
public class RelayAgentHttpStreamClient implements AgentRuntimeClient {
    private final WebClient webClient;
    private final String path;
    public RelayAgentHttpStreamClient(WebClient.Builder builder, @Value("${financeex.runtime.base-url:http://localhost:9000}") String baseUrl, @Value("${financeex.runtime.stream-path:/v1/agent/runs/stream}") String path) {
        this.webClient = builder.baseUrl(baseUrl).build(); this.path = path;
    }
    @Override public RuntimeProtocol protocol() { return RuntimeProtocol.HTTP_STREAM; }
    @Override public boolean supports(RuntimeProtocol protocol) { return protocol == RuntimeProtocol.HTTP_STREAM; }
    @Override public Flux<ChatEvent> stream(RuntimeRequest request) {
        // Runtime 当前按字符串 delta 返回，网关侧转换为统一 ChatEvent 事件流。
        Flux<String> deltas = webClient.post().uri(path).bodyValue(request).retrieve().bodyToFlux(String.class);
        if (responseMode(request) == ChatResponseMode.BLOCK) {
            return deltas.collectList()
                    .map(this::joinDeltas)
                    .map(text -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), text))
                    .flux()
                    .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
        }
        return deltas
                .map(delta -> (ChatEvent) MessageDeltaEvent.of(request.runId(), request.sessionId(), delta))
                .concatWithValues(MessageCompletedEvent.of(request.runId(), request.sessionId()));
    }

    private String joinDeltas(List<String> deltas) {
        return String.join("", deltas == null ? List.of() : deltas);
    }

    private ChatResponseMode responseMode(RuntimeRequest request) {
        return request.responseMode() == null ? ChatResponseMode.BLOCK : request.responseMode();
    }
}
