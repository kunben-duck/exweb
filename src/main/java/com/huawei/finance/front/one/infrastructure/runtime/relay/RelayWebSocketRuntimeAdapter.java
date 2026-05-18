package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Relay WebSocket 对话 API adapter。
 *
 * <p>该 adapter 是 FinanceEXChatService 后端到 RelayAgent 的出站 WebSocket。它和前端
 * `/api/v1/ex/chat/ws` 没有关系；前端 WebSocket 只订阅已经落库的 ChatEvent，不触发 Runtime query。</p>
 */
@Component
@EnableConfigurationProperties(RelayAgentProperties.class)
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class RelayWebSocketRuntimeAdapter implements RelayRuntimeProtocolAdapter {
    private static final Logger log = LoggerFactory.getLogger(RelayWebSocketRuntimeAdapter.class);

    private final WebClient.Builder webClientBuilder;
    private final RelayAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayWebSocketFrameTranslator frameTranslator;
    private final WebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    public RelayWebSocketRuntimeAdapter(WebClient.Builder webClientBuilder, RelayAgentProperties properties,
                                        ObjectMapper objectMapper, RelayWebSocketFrameTranslator frameTranslator) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.frameTranslator = frameTranslator;
    }

    @Override
    public Set<String> adapterNames() {
        return Set.of("relay-websocket");
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        URI websocketUri = websocketUri();
        return Flux.create(sink -> {
            AtomicBoolean completed = new AtomicBoolean(false);
            Disposable disposable = webSocketClient.execute(websocketUri, session -> {
                        Mono<Void> sendQuery = Mono.fromCallable(() -> objectMapper.writeValueAsString(request))
                                .map(session::textMessage)
                                .flatMap(message -> session.send(Mono.just(message)));
                        Mono<Void> receiveEvents = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .concatMap(frame -> Flux.fromIterable(frameTranslator.translate(
                                        request.runId(), request.sessionId(), frame)))
                                .doOnNext(event -> {
                                    if ("message.completed".equals(event.type())) {
                                        completed.set(true);
                                    }
                                    sink.next(event);
                                })
                                .then();
                        return sendQuery.then(receiveEvents);
                    })
                    .timeout(properties.getTimeout())
                    .subscribe(
                            ignored -> {
                            },
                            sink::error,
                            () -> {
                                if (!completed.get()) {
                                    sink.next(MessageCompletedEvent.of(request.runId(), request.sessionId()));
                                }
                                sink.complete();
                            }
                    );
            sink.onCancel(disposable::dispose);
            sink.onDispose(disposable::dispose);
        });
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        if (!properties.isCancelSupported() || properties.getStopPath() == null || properties.getStopPath().isBlank()) {
            return Mono.empty();
        }
        String path = properties.getStopPath().replace("{runId}", request.runId() == null ? "" : request.runId());
        return webClientBuilder.baseUrl(properties.getBaseUrl())
                .build()
                .post()
                .uri(path)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(properties.getTimeout())
                .onErrorResume(ex -> {
                    log.warn("Relay WebSocket cancel failed, runId={}, reason={}", request.runId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private URI websocketUri() {
        String baseUrl = properties.getBaseUrl() == null ? "" : properties.getBaseUrl().trim();
        String path = properties.getWebsocketPath() == null || properties.getWebsocketPath().isBlank()
                ? "/v1/agent/runs/ws"
                : properties.getWebsocketPath().trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        String httpUri = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) + path : baseUrl + path;
        if (httpUri.startsWith("https://")) {
            return URI.create("wss://" + httpUri.substring("https://".length()));
        }
        if (httpUri.startsWith("http://")) {
            return URI.create("ws://" + httpUri.substring("http://".length()));
        }
        return URI.create(httpUri);
    }
}
