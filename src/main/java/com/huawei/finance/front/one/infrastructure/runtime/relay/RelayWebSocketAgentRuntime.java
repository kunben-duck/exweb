package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntime;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * RelayAgent Runtime WebSocket 适配器。
 *
 * <p>当 {@code financeex.agent-runtime.provider=relay} 且
 * {@code financeex.agent-runtime.protocol=websocket} 时装配本实现。
 * 应用层仍然只依赖 AgentRuntime 防腐层；本类负责把 AgentRuntimeRequest 序列化为 WebSocket 首帧，
 * 并把 Relay 返回的文本/JSON 帧转换成标准 ChatEvent 流。取消仍优先使用本服务本地 run stop 语义，
 * 下游尽力取消复用 Relay HTTP stop 接口。</p>
 */
@Component
@EnableConfigurationProperties(RelayAgentProperties.class)
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay' "
        + "&& '${financeex.agent-runtime.protocol:http-streamable}' == 'websocket'")
public class RelayWebSocketAgentRuntime implements AgentRuntime {
    private static final Logger log = LoggerFactory.getLogger(RelayWebSocketAgentRuntime.class);

    private final WebClient.Builder webClientBuilder;
    private final RelayAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final RelayWebSocketFrameTranslator frameTranslator;
    private final WebSocketClient webSocketClient = new ReactorNettyWebSocketClient();

    public RelayWebSocketAgentRuntime(WebClient.Builder webClientBuilder, RelayAgentProperties properties,
                                      ObjectMapper objectMapper, RelayWebSocketFrameTranslator frameTranslator) {
        this.webClientBuilder = webClientBuilder;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.frameTranslator = frameTranslator;
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
                                .concatMap(frame -> Flux.fromIterable(frameTranslator.translate(request.runId(), request.sessionId(), frame)))
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
        if (properties.getStopPath() == null || properties.getStopPath().isBlank()) {
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
                    log.warn("Relay WebSocket Runtime cancel 失败，runId={}，原因：{}", request.runId(), ex.getMessage());
                    return Mono.empty();
                });
    }

    private URI websocketUri() {
        if (properties.getWebsocketUrl() != null && !properties.getWebsocketUrl().isBlank()) {
            return URI.create(properties.getWebsocketUrl().trim());
        }
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
