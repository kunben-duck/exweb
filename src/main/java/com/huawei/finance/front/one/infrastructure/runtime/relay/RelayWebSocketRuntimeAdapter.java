package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.finance.front.one.application.service.runtime.RuntimeRawStreamLogService;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
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
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;

/**
 * Relay WebSocket 普通问答 adapter。
 *
 * <p>该 adapter 每个 ChatService run 建立一条短生命周期下游 WebSocket 连接：先发送
 * {@code config}，等待配置阶段响应闭合后再发送 {@code user-message}。配置阶段 frame 只进入
 * raw log 排障链路，不进入 ChatService 标准事件流；{@code user-message} 之后的下游 frame 才复用
 * {@link RelayRuntimeResponseNormalizer} 转为标准事件。本轮只支持普通问答；{@code approval-request}
 * 等 HITL 协议事件先按 runtime 事件透传，不进入等待用户状态。</p>
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
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final ConnectionMode connectionMode;
    private final ConcurrentHashMap<ConnectionKey, ManagedConnection> cachedConnections = new ConcurrentHashMap<>();
    private final AtomicLong connectionSequence = new AtomicLong();

    @Autowired
    public RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                        RelayAgentProperties properties,
                                        AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                        RelayRuntimeResponseNormalizer responseNormalizer,
                                        ObjectProvider<RuntimeRawStreamLogService> rawStreamLogServiceProvider,
                                        ApplicationInstanceIdProvider instanceIdProvider) {
        this(objectMapper, properties, forwardCookieProperties, responseNormalizer,
                rawStreamLogServiceProvider == null ? null : rawStreamLogServiceProvider.getIfAvailable(),
                webSocketClient(properties), instanceIdProvider);
    }

    RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                 RelayAgentProperties properties,
                                 AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                 RelayRuntimeResponseNormalizer responseNormalizer,
                                 RuntimeRawStreamLogService rawStreamLogService,
                                 WebSocketClient webSocketClient,
                                 ApplicationInstanceIdProvider instanceIdProvider) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.forwardCookieProperties = forwardCookieProperties;
        this.responseNormalizer = responseNormalizer;
        this.rawStreamLogService = rawStreamLogService;
        this.webSocketClient = webSocketClient;
        this.instanceIdProvider = instanceIdProvider == null ? () -> "local" : instanceIdProvider;
        this.connectionMode = ConnectionMode.from(websocketProperties().getConnectionMode());
        validateConnectionCacheProperties();
    }

    @Override
    public Set<String> adapterNames() {
        return Set.of(ADAPTER_NAME);
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        AtomicBoolean messageCompleted = new AtomicBoolean(false);
        Flux<ChatEvent> events = connectionMode == ConnectionMode.SINGLE_INSTANCE_REUSE
                ? queryWithReusedConnection(request, messageCompleted)
                : queryWithShortConnection(request, messageCompleted);

        return events.concatWith(Mono.defer(() -> messageCompleted.get()
                ? Mono.empty()
                : Mono.just(MessageCompletedEvent.of(request.runId(), request.sessionId()))));
    }

    private Flux<ChatEvent> queryWithShortConnection(AgentRuntimeRequest request, AtomicBoolean messageCompleted) {
        return Flux.create(sink -> {
            var subscription = webSocketClient.execute(endpointUri(request), outboundHeaders(request.forwardHeaders()),
                    session -> {
                        Sinks.One<String> userMessageSink = Sinks.one();
                        Mono<Void> outbound = session.send(Flux.concat(
                                Mono.just(configMessage(request)),
                                userMessageSink.asMono()
                        ).map(session::textMessage));
                        Flux<String> frames = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frame -> validateFrameSize(frame, request.runId()))
                                .timeout(websocketProperties().getIdleTimeout());
                        if (rawStreamLogService != null) {
                            frames = rawStreamLogService.capture(frames, request, "relay", ADAPTER_NAME);
                        }
                        Flux<ChatEvent> normalized = userMessageFrames(frames, request, userMessageSink)
                                .transform(frameStream -> normalizeFrames(frameStream, request, messageCompleted))
                                .doOnNext(sink::next);
                        return Mono.when(outbound, normalized.then());
                    })
                    .subscribe(null, sink::error, sink::complete);
            sink.onCancel(subscription::dispose);
            sink.onDispose(subscription::dispose);
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private Flux<ChatEvent> queryWithReusedConnection(AgentRuntimeRequest request, AtomicBoolean messageCompleted) {
        return Flux.defer(() -> {
            ManagedConnection connection = connectionFor(request);
            ReusedExchange exchange = connection.exchange(request);
            Flux<String> frames = exchange.frames().timeout(websocketProperties().getIdleTimeout());
            if (rawStreamLogService != null) {
                frames = rawStreamLogService.capture(frames, request, "relay", ADAPTER_NAME);
            }
            Flux<String> userFrames = exchange.requiresConfigHandshake()
                    ? userMessageFrames(frames, request, connection::send)
                    : sendUserMessageThenFrames(connection, request, frames);
            return normalizeFrames(userFrames, request, messageCompleted);
        });
    }

    private Flux<ChatEvent> normalizeFrames(Flux<String> frames, AgentRuntimeRequest request,
                                            AtomicBoolean messageCompleted) {
        return frames
                .takeUntil(this::ordinaryTerminalFrame)
                .concatMap(frame -> Flux.fromIterable(responseNormalizer.normalize(
                        request.runId(), request.sessionId(), frame)))
                .takeUntil(event -> "message.completed".equals(event.type()))
                .doOnNext(event -> emitEvent(messageCompleted, event));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        if (connectionMode == ConnectionMode.SINGLE_INSTANCE_REUSE && request != null) {
            ConnectionKey key = ConnectionKey.from(request.tenantId(), request.userId(), request.sessionId());
            ManagedConnection connection = cachedConnections.remove(key);
            if (connection != null) {
                connection.close(new RelayRuntimeProtocolException("Relay WebSocket run cancelled: " + request.runId()));
            }
        }
        return Mono.empty();
    }

    private ManagedConnection connectionFor(AgentRuntimeRequest request) {
        ConnectionKey key = ConnectionKey.from(request.tenantId(), request.userId(), request.sessionId());
        ManagedConnection connection = cachedConnections.compute(key, (ignored, existing) -> {
            if (existing != null && existing.isReusable()) {
                return existing;
            }
            if (existing != null) {
                existing.close(null);
            }
            return new ManagedConnection(key, nextConnectionClientId());
        });
        evictIdleConnections();
        return connection;
    }

    private String nextConnectionClientId() {
        String instanceId = instanceIdProvider.currentInstanceId();
        String normalizedInstance = instanceId == null || instanceId.isBlank()
                ? "local"
                : instanceId.replaceAll("[^A-Za-z0-9_-]", "_");
        return normalizedInstance + "-relay-ws-" + connectionSequence.incrementAndGet();
    }

    private void evictIdleConnections() {
        int maxConnections = websocketProperties().getMaxCachedConnections();
        if (cachedConnections.size() <= maxConnections) {
            return;
        }
        cachedConnections.values().stream()
                .filter(connection -> !connection.hasActiveRun())
                .min(Comparator.comparing(ManagedConnection::lastUsedAt))
                .ifPresent(connection -> {
                    cachedConnections.remove(connection.key(), connection);
                    connection.close(null);
                });
    }

    private Flux<String> userMessageFrames(Flux<String> frames, AgentRuntimeRequest request,
                                           Sinks.One<String> userMessageSink) {
        return userMessageFrames(frames, request, message -> {
            Sinks.EmitResult result = userMessageSink.tryEmitValue(message);
            if (result.isFailure()) {
                throw new RelayRuntimeProtocolException("Failed to release Relay WebSocket user-message after "
                        + "config handshake: " + result);
            }
        });
    }

    private Flux<String> userMessageFrames(Flux<String> frames, AgentRuntimeRequest request,
                                           Consumer<String> userMessageSender) {
        return Flux.create(sink -> {
            AtomicBoolean userMessageReleased = new AtomicBoolean(false);
            AtomicReference<Disposable> frameSubscription = new AtomicReference<>();
            Disposable handshakeTimeout = Mono.delay(configHandshakeTimeout())
                    .subscribe(ignored -> {
                        if (userMessageReleased.compareAndSet(false, true)) {
                            sink.error(new RelayRuntimeProtocolException("RELAY_WS_CONFIG_TIMEOUT: Relay WebSocket "
                                    + "config handshake timed out. runId=" + request.runId()));
                            Disposable subscription = frameSubscription.get();
                            if (subscription != null) {
                                subscription.dispose();
                            }
                        }
                    }, sink::error);
            frameSubscription.set(frames.subscribe(frame -> {
                if (!userMessageReleased.get()) {
                    if (configHandshakeCompleteFrame(frame) && userMessageReleased.compareAndSet(false, true)) {
                        handshakeTimeout.dispose();
                        try {
                            userMessageSender.accept(userMessage(request));
                        } catch (RuntimeException ex) {
                            sink.error(ex);
                        }
                    }
                    return;
                }
                if (lateConfigFrame(frame)) {
                    return;
                }
                sink.next(frame);
            }, error -> {
                handshakeTimeout.dispose();
                sink.error(error);
            }, () -> {
                handshakeTimeout.dispose();
                if (!userMessageReleased.get()) {
                    sink.error(new RelayRuntimeProtocolException("Relay WebSocket closed before config handshake "
                            + "completed. runId=" + request.runId()));
                    return;
                }
                sink.complete();
            }));
            sink.onDispose(() -> {
                handshakeTimeout.dispose();
                Disposable subscription = frameSubscription.get();
                if (subscription != null) {
                    subscription.dispose();
                }
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private Flux<String> sendUserMessageThenFrames(ManagedConnection connection, AgentRuntimeRequest request,
                                                   Flux<String> frames) {
        return Flux.defer(() -> {
            try {
                connection.send(userMessage(request));
            } catch (RuntimeException ex) {
                connection.close(ex);
                return Flux.error(ex);
            }
            return frames.filter(frame -> !lateConfigFrame(frame));
        });
    }

    private void emitEvent(AtomicBoolean messageCompleted, ChatEvent event) {
        if ("message.completed".equals(event.type())) {
            messageCompleted.set(true);
        }
    }

    private String configMessage(AgentRuntimeRequest request) {
        String runtimeSessionId = runtimeSessionId(request);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", request.runtimeSessionMode() == RuntimeSessionMode.NEW ? "new" : "resume");
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
        return endpointUri(clientId);
    }

    private URI endpointUri(String clientId) {
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

    private boolean configHandshakeCompleteFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            if ("relay-end".equals(type)) {
                return true;
            }
            if ("config".equals(type)) {
                return successLike(root);
            }
            if ("session-state".equals(type)) {
                String state = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("state")));
                return "idle".equals(state) || "ready".equals(state) || "configured".equals(state);
            }
            return false;
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private boolean lateConfigFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            return "config".equals(type);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private boolean successLike(JsonNode root) {
        if (booleanValue(root.path("ready")) || booleanValue(root.path("success"))
                || booleanValue(root.path("ok")) || booleanValue(root.path("configured"))) {
            return true;
        }
        if (successText(root.path("status")) || successText(root.path("code"))
                || successText(root.path("message"))) {
            return true;
        }
        JsonNode config = root.path("config");
        return booleanValue(config.path("ready")) || booleanValue(config.path("success"))
                || booleanValue(config.path("ok")) || successText(config.path("status"));
    }

    private boolean successText(JsonNode node) {
        String value = RelayRuntimeResponseNormalizer.normalizeTypeName(text(node));
        return "success".equals(value) || "ok".equals(value) || "ready".equals(value)
                || "configured".equals(value) || "200".equals(value) || "0".equals(value);
    }

    private boolean booleanValue(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        String value = RelayRuntimeResponseNormalizer.normalizeTypeName(node.asText(null));
        return "true".equals(value) || "1".equals(value) || "yes".equals(value);
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

    private Duration configHandshakeTimeout() {
        Duration timeout = websocketProperties().getConfigHandshakeTimeout();
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
                : timeout;
    }

    private Duration idleTtl() {
        Duration ttl = websocketProperties().getIdleTtl();
        return ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofMinutes(5) : ttl;
    }

    private void validateConnectionCacheProperties() {
        if (websocketProperties().getMaxCachedConnections() <= 0) {
            throw new IllegalArgumentException(
                    "financeex.agent-runtime.relay.websocket.max-cached-connections must be greater than 0");
        }
        ConnectionMode.from(websocketProperties().getConnectionMode());
    }

    private enum ConnectionMode {
        SHORT,
        SINGLE_INSTANCE_REUSE;

        static ConnectionMode from(String value) {
            String normalized = value == null || value.isBlank() ? "short" : value.trim().toLowerCase();
            return switch (normalized) {
                case "short" -> SHORT;
                case "single-instance-reuse" -> SINGLE_INSTANCE_REUSE;
                default -> throw new IllegalArgumentException(
                        "Unsupported Relay WebSocket connection-mode: " + value
                                + ". Supported values: short, single-instance-reuse");
            };
        }
    }

    private record ConnectionKey(String tenantId, String userId, String sessionId) {
        static ConnectionKey from(String tenantId, String userId, String sessionId) {
            return new ConnectionKey(
                    tenantId == null ? "" : tenantId,
                    userId == null ? "" : userId,
                    sessionId == null ? "" : sessionId);
        }
    }

    private record ReusedExchange(Flux<String> frames, boolean requiresConfigHandshake) {
    }

    private final class ManagedConnection {
        private final ConnectionKey key;
        private final String clientId;
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final AtomicBoolean started = new AtomicBoolean(false);
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private final AtomicReference<ReusedRunExchange> activeRun = new AtomicReference<>();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private volatile Instant lastUsedAt = Instant.now();

        private ManagedConnection(ConnectionKey key, String clientId) {
            this.key = key;
            this.clientId = clientId;
        }

        ReusedExchange exchange(AgentRuntimeRequest request) {
            if (closed.get()) {
                return new ReusedExchange(Flux.error(new RelayRuntimeProtocolException(
                        "Relay WebSocket cached connection is closed")), false);
            }
            ReusedRunExchange run = new ReusedRunExchange(request.runId());
            if (!activeRun.compareAndSet(null, run)) {
                return new ReusedExchange(Flux.error(new RelayRuntimeProtocolException(
                        "Relay WebSocket cached connection already has an active run. sessionId="
                                + request.sessionId())), false);
            }
            boolean firstExchange = started.compareAndSet(false, true);
            if (firstExchange) {
                connect(request);
                try {
                    send(configMessage(request));
                } catch (RuntimeException ex) {
                    close(ex);
                    return new ReusedExchange(Flux.error(ex), false);
                }
            }
            Flux<String> frames = run.frames()
                    .doFinally(signal -> {
                        activeRun.compareAndSet(run, null);
                        lastUsedAt = Instant.now();
                        scheduleIdleClose(lastUsedAt);
                    });
            return new ReusedExchange(frames, firstExchange);
        }

        void send(String message) {
            Sinks.EmitResult result = outbound.tryEmitNext(message);
            if (result.isFailure()) {
                throw new RelayRuntimeProtocolException("Relay WebSocket outbound emit failed: " + result);
            }
        }

        boolean isReusable() {
            if (closed.get()) {
                return false;
            }
            if (hasActiveRun()) {
                return true;
            }
            if (Instant.now().isAfter(lastUsedAt.plus(idleTtl()))) {
                close(null);
                return false;
            }
            return true;
        }

        boolean hasActiveRun() {
            return activeRun.get() != null;
        }

        Instant lastUsedAt() {
            return lastUsedAt;
        }

        ConnectionKey key() {
            return key;
        }

        void close(Throwable cause) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            ReusedRunExchange run = activeRun.getAndSet(null);
            if (run != null) {
                run.errorOrComplete(cause);
            }
            outbound.tryEmitComplete();
            Disposable disposable = subscription.getAndSet(null);
            if (disposable != null) {
                disposable.dispose();
            }
            cachedConnections.remove(key, this);
        }

        private void connect(AgentRuntimeRequest request) {
            Disposable disposable = webSocketClient.execute(endpointUri(clientId), outboundHeaders(request.forwardHeaders()),
                    session -> {
                        Mono<Void> sender = session.send(outbound.asFlux().map(session::textMessage));
                        Mono<Void> receiver = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frame -> validateFrameSize(frame, request.runId()))
                                .doOnNext(this::emitFrame)
                                .then()
                                .doFinally(signal -> close(null));
                        return Mono.when(sender, receiver);
                    })
                    .subscribe(null, this::close, () -> close(null));
            subscription.set(disposable);
        }

        private void emitFrame(String frame) {
            ReusedRunExchange run = activeRun.get();
            if (run != null) {
                run.emit(frame);
            }
        }

        private void scheduleIdleClose(Instant observedLastUsedAt) {
            Mono.delay(idleTtl()).subscribe(ignored -> {
                if (!hasActiveRun() && observedLastUsedAt.equals(lastUsedAt)
                        && Instant.now().isAfter(lastUsedAt.plus(idleTtl()).minusMillis(1))) {
                    close(null);
                }
            });
        }
    }

    private static final class ReusedRunExchange {
        private final String runId;
        private final Sinks.Many<String> frames = Sinks.many().unicast().onBackpressureBuffer();

        private ReusedRunExchange(String runId) {
            this.runId = runId;
        }

        Flux<String> frames() {
            return frames.asFlux();
        }

        void emit(String frame) {
            Sinks.EmitResult result = frames.tryEmitNext(frame);
            if (result.isFailure()) {
                frames.tryEmitError(new RelayRuntimeProtocolException(
                        "Relay WebSocket inbound emit failed, runId=" + runId + ", result=" + result));
            }
        }

        void errorOrComplete(Throwable cause) {
            if (cause == null) {
                frames.tryEmitComplete();
            } else {
                frames.tryEmitError(cause);
            }
        }
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
