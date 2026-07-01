package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.service.runtime.RuntimeRawStreamLogService;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

/**
 * Relay WebSocket 普通问答 adapter。
 *
 * <p>该 adapter 每个 ChatService run 建立一条短生命周期下游 WebSocket 连接：先发送
 * {@code config}，再发送 {@code user-message}，随后把下游帧复用
 * {@link RelayRuntimeResponseNormalizer} 转为 ChatService 标准事件。本轮只支持普通问答；
 * {@code approval-request} 等 HITL 协议事件先按 runtime 事件透传，不进入等待用户状态。</p>
 */
@Component
@EnableConfigurationProperties({RelayAgentProperties.class, AgentRuntimeForwardCookieProperties.class})
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class RelayWebSocketRuntimeAdapter implements RelayRuntimeProtocolAdapter {
    static final String ADAPTER_NAME = "relay-websocket";

    private final ObjectMapper objectMapper;
    private final RelayAgentProperties properties;
    private final AgentRuntimeForwardCookieProperties forwardCookieProperties;
    private final RelayRuntimeResponseNormalizer responseNormalizer;
    private final RuntimeRawStreamLogService rawStreamLogService;
    private final WebSocketClient webSocketClient;

    @Autowired
    public RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                        RelayAgentProperties properties,
                                        AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                        RelayRuntimeResponseNormalizer responseNormalizer,
                                        ObjectProvider<RuntimeRawStreamLogService> rawStreamLogServiceProvider) {
        this(objectMapper, properties, forwardCookieProperties, responseNormalizer,
                rawStreamLogServiceProvider == null ? null : rawStreamLogServiceProvider.getIfAvailable(),
                webSocketClient(properties));
    }

    RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                 RelayAgentProperties properties,
                                 AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                 RelayRuntimeResponseNormalizer responseNormalizer,
                                 RuntimeRawStreamLogService rawStreamLogService,
                                 WebSocketClient webSocketClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.forwardCookieProperties = forwardCookieProperties;
        this.responseNormalizer = responseNormalizer;
        this.rawStreamLogService = rawStreamLogService;
        this.webSocketClient = webSocketClient;
    }

    @Override
    public Set<String> adapterNames() {
        return Set.of(ADAPTER_NAME);
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        AtomicBoolean messageCompleted = new AtomicBoolean(false);
        Flux<ChatEvent> events = Flux.create(sink -> {
            var subscription = webSocketClient.execute(endpointUri(request), outboundHeaders(request.forwardHeaders()),
                    session -> {
                        Mono<Void> sendInitialMessages = session.send(Flux.just(
                                session.textMessage(configMessage(request)),
                                session.textMessage(userMessage(request))
                        ));
                        Flux<String> frames = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frame -> validateFrameSize(frame, request.runId()))
                                .timeout(websocketProperties().getIdleTimeout());
                        if (rawStreamLogService != null) {
                            frames = rawStreamLogService.capture(frames, request, "relay", ADAPTER_NAME);
                        }
                        Flux<ChatEvent> normalized = frames
                                .takeUntil(this::ordinaryTerminalFrame)
                                .concatMap(frame -> Flux.fromIterable(responseNormalizer.normalize(
                                        request.runId(), request.sessionId(), frame)))
                                .takeUntil(event -> "message.completed".equals(event.type()))
                                .doOnNext(event -> emitEvent(sink, messageCompleted, event));
                        return sendInitialMessages.thenMany(normalized).then();
                    })
                    .subscribe(null, sink::error, sink::complete);
            sink.onCancel(subscription::dispose);
            sink.onDispose(subscription::dispose);
        }, FluxSink.OverflowStrategy.BUFFER);

        return events.concatWith(Mono.defer(() -> messageCompleted.get()
                ? Mono.empty()
                : Mono.just(MessageCompletedEvent.of(request.runId(), request.sessionId()))));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        /*
         * WebSocket adapter 的下游连接随 run 订阅生命周期关闭。ChatService stop 会取消上游订阅；
         * 如果 Relay 后续提供独立 cancel 帧或 HTTP cancel API，可在这里补充协议级通知。
         */
        return Mono.empty();
    }

    private void emitEvent(FluxSink<ChatEvent> sink, AtomicBoolean messageCompleted, ChatEvent event) {
        if ("message.completed".equals(event.type())) {
            messageCompleted.set(true);
        }
        sink.next(event);
    }

    private String configMessage(AgentRuntimeRequest request) {
        String runtimeSessionId = runtimeSessionId(request);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", request.runtimeSessionId() == null || request.runtimeSessionId().isBlank()
                ? "new"
                : "resume");
        config.put("sessionId", runtimeSessionId);
        config.put("uid", request.userId());
        if (websocketProperties().getAppMode() != null && !websocketProperties().getAppMode().isBlank()) {
            config.put("appMode", websocketProperties().getAppMode());
        }
        return toJson(Map.of("type", "config", "config", Map.copyOf(config)));
    }

    private String userMessage(AgentRuntimeRequest request) {
        return toJson(Map.of("type", "user-message", "content", request.message() == null ? "" : request.message()));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new RelayRuntimeProtocolException("Failed to serialize Relay WebSocket request: " + ex.getMessage());
        }
    }

    private String runtimeSessionId(AgentRuntimeRequest request) {
        if (request.runtimeSessionId() != null && !request.runtimeSessionId().isBlank()) {
            return request.runtimeSessionId();
        }
        return request.runId() == null || request.runId().isBlank()
                ? "relay-" + UUID.randomUUID()
                : request.runId();
    }

    private URI endpointUri(AgentRuntimeRequest request) {
        String clientId = request.runId() == null || request.runId().isBlank()
                ? UUID.randomUUID().toString()
                : request.runId();
        String configured = websocketProperties().getUrl();
        String base = configured == null || configured.isBlank() ? "ws://localhost:8080/ws" : configured.trim();
        String uri = base.contains("{clientId}")
                ? base.replace("{clientId}", clientId)
                : appendPathSegment(base, clientId);
        return URI.create(uri);
    }

    private String appendPathSegment(String base, String segment) {
        return base.endsWith("/") ? base + segment : base + "/" + segment;
    }

    private HttpHeaders outboundHeaders(RuntimeForwardHeaders forwardHeaders) {
        HttpHeaders headers = new HttpHeaders();
        if (forwardCookieProperties.isAdapterAllowed(ADAPTER_NAME)
                && forwardHeaders != null && forwardHeaders.hasCookie()) {
            headers.set(HttpHeaders.COOKIE, forwardHeaders.cookieHeader());
        }
        return headers;
    }

    private boolean ordinaryTerminalFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = text(root.path("type"));
            if (!"session-state".equals(RelayRuntimeResponseNormalizer.normalizeTypeName(type))) {
                return false;
            }
            String state = text(root.path("state"));
            if (state == null) {
                return false;
            }
            String normalizedState = RelayRuntimeResponseNormalizer.normalizeTypeName(state);
            return "idle".equals(normalizedState) || "completed".equals(normalizedState);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private void validateFrameSize(String frame, String runId) {
        int maxBytes = maxFrameBytes();
        int actualBytes = frame == null ? 0 : frame.getBytes(StandardCharsets.UTF_8).length;
        if (actualBytes > maxBytes) {
            throw new RelayRuntimeProtocolException("Relay WebSocket frame exceeds max size. runId="
                    + runId + ", maxBytes=" + maxBytes + ", actualBytes=" + actualBytes);
        }
    }

    private int maxFrameBytes() {
        DataSize size = websocketProperties().getMaxFrameBytes();
        if (size == null || size.toBytes() <= 0) {
            throw new IllegalArgumentException("financeex.agent-runtime.relay.websocket.max-frame-bytes must be greater than 0");
        }
        if (size.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "financeex.agent-runtime.relay.websocket.max-frame-bytes must not exceed "
                            + Integer.MAX_VALUE + " bytes");
        }
        return (int) size.toBytes();
    }

    private RelayAgentProperties.WebSocket websocketProperties() {
        return properties.getRelay().getWebsocket();
    }

    private String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value;
    }

    private static WebSocketClient webSocketClient(RelayAgentProperties properties) {
        RelayAgentProperties.WebSocket websocket = properties.getRelay().getWebsocket();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeoutMillis(websocket.getConnectTimeout()));
        ReactorNettyWebSocketClient client = new ReactorNettyWebSocketClient(httpClient);
        client.setMaxFramePayloadLength(maxFrameBytes(websocket.getMaxFrameBytes()));
        return client;
    }

    private static int connectTimeoutMillis(Duration timeout) {
        Duration safeTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
        long millis = safeTimeout.toMillis();
        return millis > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(1L, millis);
    }

    private static int maxFrameBytes(DataSize size) {
        if (size == null || size.toBytes() <= 0) {
            throw new IllegalArgumentException("financeex.agent-runtime.relay.websocket.max-frame-bytes must be greater than 0");
        }
        if (size.toBytes() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "financeex.agent-runtime.relay.websocket.max-frame-bytes must not exceed "
                            + Integer.MAX_VALUE + " bytes");
        }
        return (int) size.toBytes();
    }
}
