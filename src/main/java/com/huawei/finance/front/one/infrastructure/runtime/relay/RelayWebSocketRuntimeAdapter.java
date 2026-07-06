package com.huawei.finance.front.one.infrastructure.runtime.relay;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.AgentRuntimeForwardCookieProperties;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeCancelRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeHitlResponseRequest;
import com.huawei.finance.front.one.application.integration.agent.AgentRuntimeRequest;
import com.huawei.finance.front.one.application.integration.agent.RuntimeForwardHeaders;
import com.huawei.finance.front.one.application.integration.agent.RuntimeSessionMode;
import com.huawei.finance.front.one.domain.chat.ChatEvent;
import com.huawei.finance.front.one.domain.chat.MessageCompletedEvent;
import io.netty.channel.ChannelOption;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>每个 ChatService run 都建立一条短生命周期下游 WebSocket，先完成 {@code config} 阶段，再发送
 * {@code user-message}。配置阶段 frame 只用于握手判定，不进入 ChatService 标准事件流；{@code user-message}
 * 之后的下游 frame 才复用 {@link RelayRuntimeResponseNormalizer} 转为标准事件。本轮只支持普通问答；
 * {@code approval-request} 等 HITL 协议事件先按 runtime 事件透传，不进入等待用户状态。</p>
 */
@Component
@EnableConfigurationProperties({RelayAgentProperties.class, AgentRuntimeForwardCookieProperties.class})
@ConditionalOnExpression("'${financeex.agent-runtime.provider:relay}' == 'relay'")
public class RelayWebSocketRuntimeAdapter implements RelayRuntimeProtocolAdapter {
    static final String ADAPTER_NAME = "relay-websocket";
    private static final Logger log = LoggerFactory.getLogger(RelayWebSocketRuntimeAdapter.class);

    private final ObjectMapper objectMapper;
    private final RelayAgentProperties properties;
    private final AgentRuntimeForwardCookieProperties forwardCookieProperties;
    private final RelayRuntimeResponseNormalizer responseNormalizer;
    private final WebSocketClient webSocketClient;
    /** Active run -> outbound exchange, used only for best-effort Relay WS interrupt on stop/delete. */
    private final ConcurrentHashMap<String, ActiveRelayWebSocketExchange> activeExchanges = new ConcurrentHashMap<>();

    @Autowired
    public RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                        RelayAgentProperties properties,
                                        AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                        RelayRuntimeResponseNormalizer responseNormalizer) {
        this(objectMapper, properties, forwardCookieProperties, responseNormalizer, webSocketClient(properties));
    }

    RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                 RelayAgentProperties properties,
                                 AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                 RelayRuntimeResponseNormalizer responseNormalizer,
                                 WebSocketClient webSocketClient) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.forwardCookieProperties = forwardCookieProperties;
        this.responseNormalizer = responseNormalizer;
        this.webSocketClient = webSocketClient;
    }

    @Override
    public Set<String> adapterNames() {
        return Set.of(ADAPTER_NAME);
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        AtomicBoolean messageCompleted = new AtomicBoolean(false);
        Flux<ChatEvent> events = queryWithShortConnection(request, messageCompleted);

        return events.concatWith(Mono.defer(() -> messageCompleted.get()
                ? Mono.empty()
                : Mono.just(MessageCompletedEvent.of(request.runId(), request.sessionId()))));
    }

    @Override
    public boolean supportsUserResponseContinuation() {
        return true;
    }

    @Override
    public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeHitlResponseRequest request) {
        AtomicBoolean messageCompleted = new AtomicBoolean(false);
        Flux<ChatEvent> events = hitlWithShortConnection(request, messageCompleted);
        return events.concatWith(Mono.defer(() -> messageCompleted.get()
                ? Mono.empty()
                : Mono.just(MessageCompletedEvent.of(request.runId(), request.sessionId()))));
    }

    private Flux<ChatEvent> queryWithShortConnection(AgentRuntimeRequest request, AtomicBoolean messageCompleted) {
        return Flux.create(sink -> {
            ShortRunExchange exchange = new ShortRunExchange(request.runId());
            registerActiveExchange(request.runId(), exchange);
            var subscription = webSocketClient.execute(endpointUri(request), outboundHeaders(request.forwardHeaders()),
                    session -> {
                        Mono<Void> outbound = session.send(exchange.outbound(configMessage(request))
                                .map(session::textMessage));
                        Flux<String> frames = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frame -> validateFrameSize(frame, request.runId()));
                        Flux<ChatEvent> normalized = userMessageFrames(frames, request, exchange)
                                .transform(frameStream -> normalizeFrames(frameStream, request.runId(),
                                        request.sessionId(), messageCompleted))
                                .doOnNext(sink::next)
                                .doFinally(signal -> exchange.completeSending());
                        return Mono.when(outbound, normalized.then());
                    })
                    .doFinally(signal -> activeExchanges.remove(request.runId(), exchange))
                    .subscribe(null, sink::error, sink::complete);
            exchange.subscription(subscription);
            sink.onCancel(subscription::dispose);
            sink.onDispose(() -> {
                activeExchanges.remove(request.runId(), exchange);
                exchange.close(null);
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private Flux<ChatEvent> hitlWithShortConnection(AgentRuntimeHitlResponseRequest request,
                                                    AtomicBoolean messageCompleted) {
        return Flux.create(sink -> {
            ShortRunExchange exchange = new ShortRunExchange(request.runId());
            registerActiveExchange(request.runId(), exchange);
            var subscription = webSocketClient.execute(endpointUri(request.runId()), outboundHeaders(request.forwardHeaders()),
                    session -> {
                        Mono<Void> outbound = session.send(exchange.outbound(configMessage(request))
                                .map(session::textMessage));
                        Flux<String> frames = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frame -> validateFrameSize(frame, request.runId()));
                        Flux<ChatEvent> normalized = hitlResponseFrames(frames, request, exchange)
                                .transform(frameStream -> normalizeFrames(frameStream, request.runId(),
                                        request.sessionId(), messageCompleted))
                                .doOnNext(sink::next)
                                .doFinally(signal -> exchange.completeSending());
                        return Mono.when(outbound, normalized.then());
                    })
                    .doFinally(signal -> activeExchanges.remove(request.runId(), exchange))
                    .subscribe(null, sink::error, sink::complete);
            exchange.subscription(subscription);
            sink.onCancel(subscription::dispose);
            sink.onDispose(() -> {
                activeExchanges.remove(request.runId(), exchange);
                exchange.close(null);
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private Flux<ChatEvent> normalizeFrames(Flux<String> frames, String runId, String sessionId,
                                            AtomicBoolean messageCompleted) {
        return frames
                .takeUntil(this::ordinaryTerminalFrame)
                .concatMap(frame -> Flux.fromIterable(responseNormalizer.normalize(
                        runId, sessionId, frame)))
                .takeUntil(event -> "message.completed".equals(event.type()))
                .doOnNext(event -> emitEvent(messageCompleted, event));
    }

    @Override
    public Mono<Void> cancel(AgentRuntimeCancelRequest request) {
        return Mono.defer(() -> {
                    if (request == null || request.runId() == null || request.runId().isBlank()) {
                        return Mono.empty();
                    }
                    ActiveRelayWebSocketExchange exchange = activeExchanges.remove(request.runId());
                    if (exchange != null) {
                        log.info("Relay WebSocket interrupt uses active exchange. runId={}", request.runId());
                        return Mono.fromRunnable(() -> exchange.interrupt(request.runId()));
                    }
                    return interruptViaResumeConnection(request);
                })
                .onErrorResume(ex -> {
                    String runId = request == null ? null : request.runId();
                    log.warn("Relay WebSocket interrupt failed, runId={}, reason={}", runId, ex.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> interruptViaResumeConnection(AgentRuntimeCancelRequest request) {
        if (blank(request.runtimeSessionId()) && blank(request.sessionId())) {
            log.debug("Relay WebSocket active exchange not found and session id is empty on cancel. runId={}",
                    request.runId());
            return Mono.empty();
        }
        String clientId = interruptClientId(request.runId());
        String relaySessionId = relaySessionIdForCancel(request);
        return webSocketClient.execute(endpointUri(clientId), outboundHeaders(request.forwardHeaders()), session -> {
            Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
            Mono<Void> outboundSend = session.send(Flux.concat(Mono.just(configMessage(request)), outbound.asFlux())
                    .map(session::textMessage));
            Flux<String> frames = session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(frame -> validateFrameSize(frame, request.runId()))
                    .timeout(websocketProperties().getIdleTimeout());
            Mono<Void> releaseInterrupt = waitForInterruptPausedAck(frames, outbound, request, clientId, relaySessionId)
                    .doFinally(signal -> outbound.tryEmitComplete())
                    .then(session.close())
                    .onErrorResume(error -> session.close().then(Mono.error(error)));
            log.info("Relay WebSocket interrupt opens temporary resume connection. runId={}, runtimeSessionId={}, clientId={}",
                    request.runId(), relaySessionId, clientId);
            return Mono.when(outboundSend, releaseInterrupt);
        });
    }

    private Mono<Void> waitForInterruptPausedAck(Flux<String> frames, Sinks.Many<String> outbound,
                                                 AgentRuntimeCancelRequest request, String clientId,
                                                 String relaySessionId) {
        return Mono.create(sink -> {
            AtomicBoolean done = new AtomicBoolean(false);
            AtomicBoolean configReady = new AtomicBoolean(false);
            AtomicBoolean interruptSent = new AtomicBoolean(false);
            AtomicReference<Disposable> frameSubscription = new AtomicReference<>();
            AtomicReference<Disposable> configTimeout = new AtomicReference<>();
            AtomicReference<Disposable> ackTimeout = new AtomicReference<>();
            Runnable cleanup = () -> {
                Disposable configTimer = configTimeout.getAndSet(null);
                if (configTimer != null) {
                    configTimer.dispose();
                }
                Disposable ackTimer = ackTimeout.getAndSet(null);
                if (ackTimer != null) {
                    ackTimer.dispose();
                }
                Disposable subscription = frameSubscription.getAndSet(null);
                if (subscription != null) {
                    subscription.dispose();
                }
            };
            Consumer<Throwable> fail = error -> {
                if (done.compareAndSet(false, true)) {
                    cleanup.run();
                    sink.error(error);
                }
            };
            Runnable complete = () -> {
                if (done.compareAndSet(false, true)) {
                    cleanup.run();
                    sink.success();
                }
            };
            configTimeout.set(Mono.delay(configHandshakeTimeout()).subscribe(ignored -> fail.accept(
                    new RelayRuntimeProtocolException("RELAY_WS_CONFIG_TIMEOUT: Relay WebSocket interrupt config "
                            + "handshake timed out. runId=" + request.runId())), fail));
            frameSubscription.set(frames.subscribe(frame -> {
                if (done.get()) {
                    return;
                }
                try {
                    if (!configReady.get()) {
                        RelayRuntimeProtocolException configFailure = configHandshakeFailure(frame);
                        if (configFailure != null) {
                            fail.accept(configFailure);
                            return;
                        }
                        if (configHandshakeCompleteFrame(frame)) {
                            configReady.set(true);
                            Disposable configTimer = configTimeout.getAndSet(null);
                            if (configTimer != null) {
                                configTimer.dispose();
                            }
                            emitInterrupt(outbound, request.runId());
                            interruptSent.set(true);
                            log.info("Relay WebSocket interrupt sent. runId={}, runtimeSessionId={}, clientId={}, interruptSent=true",
                                    request.runId(), relaySessionId, clientId);
                            ackTimeout.set(Mono.delay(interruptAckTimeout()).subscribe(ignored -> {
                                if (done.compareAndSet(false, true)) {
                                    cleanup.run();
                                    log.warn("Relay WebSocket interrupt paused ack timed out. runId={}, runtimeSessionId={}, "
                                                    + "clientId={}, interruptSent=true, pausedAck=false",
                                            request.runId(), relaySessionId, clientId);
                                    sink.success();
                                }
                            }, fail));
                        }
                        return;
                    }
                    RelayRuntimeProtocolException failure = configHandshakeFailure(frame);
                    if (failure != null) {
                        fail.accept(failure);
                        return;
                    }
                    if (interruptPausedAckFrame(frame)) {
                        log.info("Relay WebSocket interrupt paused ack received. runId={}, runtimeSessionId={}, "
                                        + "clientId={}, interruptSent={}, pausedAck=true",
                                request.runId(), relaySessionId, clientId, interruptSent.get());
                        complete.run();
                    }
                } catch (Throwable ex) {
                    fail.accept(ex);
                }
            }, fail, () -> {
                if (done.get()) {
                    return;
                }
                if (!configReady.get()) {
                    fail.accept(new RelayRuntimeProtocolException("Relay WebSocket closed before interrupt config "
                            + "handshake completed. runId=" + request.runId()));
                    return;
                }
                log.warn("Relay WebSocket interrupt connection closed before paused ack. runId={}, runtimeSessionId={}, "
                                + "clientId={}, interruptSent={}, pausedAck=false",
                        request.runId(), relaySessionId, clientId, interruptSent.get());
                complete.run();
            }));
            sink.onDispose(cleanup::run);
        });
    }

    private void emitInterrupt(Sinks.Many<String> outbound, String runId) {
        Sinks.EmitResult result = outbound.tryEmitNext(interruptMessage());
        if (result.isFailure()) {
            throw new RelayRuntimeProtocolException("Relay WebSocket interrupt outbound emit failed. runId="
                    + runId + ", result=" + result);
        }
        outbound.tryEmitComplete();
    }

    private void registerActiveExchange(String runId, ActiveRelayWebSocketExchange exchange) {
        if (runId == null || runId.isBlank() || exchange == null) {
            return;
        }
        activeExchanges.put(runId, exchange);
    }

    private Flux<String> userMessageFrames(Flux<String> frames, AgentRuntimeRequest request,
                                           ShortRunExchange exchange) {
        return businessFramesAfterConfig(frames, request.runId(), exchange, userMessage(request));
    }

    private Flux<String> hitlResponseFrames(Flux<String> frames, AgentRuntimeHitlResponseRequest request,
                                            ShortRunExchange exchange) {
        return businessFramesAfterConfig(frames, request.runId(), exchange, approvalResponseMessage(request));
    }

    private Flux<String> businessFramesAfterConfig(Flux<String> frames, String runId,
                                                   ShortRunExchange exchange, String initialBusinessMessage) {
        return Flux.create(sink -> {
            AtomicBoolean done = new AtomicBoolean(false);
            AtomicBoolean userMessageReleased = new AtomicBoolean(false);
            AtomicBoolean responseStarted = new AtomicBoolean(false);
            AtomicBoolean terminalFrameSeen = new AtomicBoolean(false);
            AtomicReference<Disposable> frameSubscription = new AtomicReference<>();
            AtomicReference<Disposable> handshakeTimeout = new AtomicReference<>();
            AtomicReference<Disposable> heartbeatTimer = new AtomicReference<>();
            AtomicReference<Disposable> livenessTimer = new AtomicReference<>();
            AtomicReference<Disposable> maxRunTimer = new AtomicReference<>();
            AtomicLong lastInboundNanos = new AtomicLong(System.nanoTime());
            Runnable cleanup = () -> {
                Disposable handshake = handshakeTimeout.getAndSet(null);
                if (handshake != null) {
                    handshake.dispose();
                }
                Disposable heartbeat = heartbeatTimer.getAndSet(null);
                if (heartbeat != null) {
                    heartbeat.dispose();
                }
                Disposable liveness = livenessTimer.getAndSet(null);
                if (liveness != null) {
                    liveness.dispose();
                }
                Disposable maxRun = maxRunTimer.getAndSet(null);
                if (maxRun != null) {
                    maxRun.dispose();
                }
                Disposable subscription = frameSubscription.getAndSet(null);
                if (subscription != null) {
                    subscription.dispose();
                }
            };
            Consumer<Throwable> fail = error -> {
                if (done.compareAndSet(false, true)) {
                    cleanup.run();
                    sink.error(error);
                }
            };
            Runnable complete = () -> {
                if (done.compareAndSet(false, true)) {
                    cleanup.run();
                    sink.complete();
                }
            };
            handshakeTimeout.set(Mono.delay(configHandshakeTimeout())
                    .subscribe(ignored -> {
                        if (userMessageReleased.compareAndSet(false, true)) {
                            fail.accept(new RelayRuntimeProtocolException("RELAY_WS_CONFIG_TIMEOUT: Relay WebSocket "
                                    + "config handshake timed out. runId=" + runId));
                        }
                    }, fail));
            frameSubscription.set(frames.subscribe(frame -> {
                if (done.get()) {
                    return;
                }
                if (!userMessageReleased.get()) {
                    RelayRuntimeProtocolException configFailure = configHandshakeFailure(frame);
                    if (configFailure != null && userMessageReleased.compareAndSet(false, true)) {
                        fail.accept(configFailure);
                        return;
                    }
                    if (configHandshakeCompleteFrame(frame) && userMessageReleased.compareAndSet(false, true)) {
                        Disposable handshake = handshakeTimeout.getAndSet(null);
                        if (handshake != null) {
                            handshake.dispose();
                        }
                        /*
                         * session-ready 是 config 阶段唯一完成信号，也携带 Relay 确认的 session_id。
                         * 这里只放行这一条受控 metadata，让 run 表和 RuntimeBinding 尽早学习真实
                         * runtimeSessionId；其他 config 初始化帧仍然隔离，避免污染用户回答流。
                         */
                        sink.next(frame);
                        try {
                            exchange.send(initialBusinessMessage);
                            startRunControls(new RunControlContext(exchange, runId, heartbeatTimer, livenessTimer,
                                    maxRunTimer, lastInboundNanos, fail));
                        } catch (RuntimeException ex) {
                            fail.accept(ex);
                        }
                    }
                    return;
                }
                lastInboundNanos.set(System.nanoTime());
                if (!shouldEmitUserResponseFrame(frame, responseStarted)) {
                    return;
                }
                if (ordinaryTerminalFrame(frame) || terminalTextFrame(frame)) {
                    terminalFrameSeen.set(true);
                }
                sink.next(frame);
            }, error -> {
                fail.accept(error);
            }, () -> {
                if (!userMessageReleased.get()) {
                    fail.accept(new RelayRuntimeProtocolException("Relay WebSocket closed before config handshake "
                            + "completed. runId=" + runId));
                    return;
                }
                if (terminalFrameSeen.get()) {
                    complete.run();
                    return;
                }
                fail.accept(new RelayRuntimeProtocolException("RELAY_WS_CLOSED_BEFORE_TERMINAL: Relay WebSocket "
                        + "closed before terminal session-state. runId=" + runId));
            }));
            sink.onDispose(() -> {
                done.set(true);
                cleanup.run();
            });
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private void startRunControls(RunControlContext context) {
        context.lastInboundNanos().set(System.nanoTime());
        context.heartbeatTimer().set(Flux.interval(heartbeatInterval()).subscribe(ignored -> {
            try {
                context.exchange().send(heartbeatMessage());
            } catch (Throwable ex) {
                context.fail().accept(ex);
            }
        }, context.fail()));
        Duration heartbeatResponseTimeout = heartbeatResponseTimeout();
        if (!heartbeatResponseTimeout.isZero() && !heartbeatResponseTimeout.isNegative()) {
            context.livenessTimer().set(Flux.interval(livenessCheckInterval(heartbeatResponseTimeout)).subscribe(ignored -> {
                long elapsedNanos = System.nanoTime() - context.lastInboundNanos().get();
                if (elapsedNanos < durationToNanos(heartbeatResponseTimeout)) {
                    return;
                }
                try {
                    context.exchange().interrupt(context.runId());
                } catch (Throwable ex) {
                    log.warn("Relay WebSocket heartbeat timeout interrupt failed. runId={}, reason={}",
                            context.runId(), ex.getMessage());
                }
                context.fail().accept(new RelayRuntimeProtocolException("RELAY_WS_HEARTBEAT_RESPONSE_TIMEOUT: Relay "
                        + "WebSocket heartbeat response timed out. runId=" + context.runId()
                        + ", timeout=" + heartbeatResponseTimeout));
            }, context.fail()));
        }
        context.maxRunTimer().set(Mono.delay(maxRunDuration()).subscribe(ignored -> {
            try {
                context.exchange().interrupt(context.runId());
            } catch (Throwable ex) {
                log.warn("Relay WebSocket max run duration interrupt failed. runId={}, reason={}",
                        context.runId(), ex.getMessage());
            }
            context.fail().accept(new RelayRuntimeProtocolException("RELAY_WS_MAX_RUN_DURATION_EXCEEDED: Relay WebSocket "
                    + "run exceeded max duration. runId=" + context.runId()
                    + ", maxRunDuration=" + maxRunDuration()));
        }, context.fail()));
    }

    private boolean shouldEmitUserResponseFrame(String frame, AtomicBoolean responseStarted) {
        if (lateConfigFrame(frame)) {
            return false;
        }
        if (heartbeatFrame(frame)) {
            return false;
        }
        if (responseStarted.get()) {
            return true;
        }
        if (userResponseStartFrame(frame)) {
            responseStarted.set(true);
            return true;
        }
        return false;
    }

    private void emitEvent(AtomicBoolean messageCompleted, ChatEvent event) {
        if ("message.completed".equals(event.type())) {
            messageCompleted.set(true);
        }
    }

    private String configMessage(AgentRuntimeRequest request) {
        String relaySessionId = relaySessionIdForQuery(request);
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", request.runtimeSessionMode() == RuntimeSessionMode.NEW ? "new" : "resume");
        config.put("sessionId", relaySessionId);
        config.put("uid", request.userId());
        if (request.runtimeSessionMode() == RuntimeSessionMode.RESUME) {
            config.put("supports_incremental_recovery", true);
        }
        if (websocketProperties().getAppMode() != null && !websocketProperties().getAppMode().isBlank()) {
            config.put("appMode", websocketProperties().getAppMode());
        }
        return toJson(Map.of("type", "config", "config", Map.copyOf(config)));
    }

    private String configMessage(AgentRuntimeCancelRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", "resume");
        config.put("sessionId", relaySessionIdForCancel(request));
        config.put("uid", request.userId());
        config.put("supports_incremental_recovery", true);
        if (websocketProperties().getAppMode() != null && !websocketProperties().getAppMode().isBlank()) {
            config.put("appMode", websocketProperties().getAppMode());
        }
        return toJson(Map.of("type", "config", "config", Map.copyOf(config)));
    }

    private String configMessage(AgentRuntimeHitlResponseRequest request) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("sessionMode", "resume");
        config.put("sessionId", blank(request.runtimeSessionId()) ? request.sessionId() : request.runtimeSessionId());
        config.put("uid", request.userId());
        config.put("supports_incremental_recovery", true);
        if (websocketProperties().getAppMode() != null && !websocketProperties().getAppMode().isBlank()) {
            config.put("appMode", websocketProperties().getAppMode());
        }
        return toJson(Map.of("type", "config", "config", Map.copyOf(config)));
    }

    private String userMessage(AgentRuntimeRequest request) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "user-message");
        message.put("content", request.message() == null ? "" : request.message());
        Map<String, Object> metadata = RelayRuntimeWireRequestMapper.sanitizedMetadata(request.metadata());
        if (!metadata.isEmpty()) {
            message.put("metadata", metadata);
        }
        return toJson(message);
    }

    private String approvalResponseMessage(AgentRuntimeHitlResponseRequest request) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "approval-response");
        message.put("request_id", request.approvalId());
        message.put("approved", booleanValue(request.responsePayload().get("approved")));
        message.put("scope", stringOrDefault(request.responsePayload().get("scope"), "once"));
        Object answers = request.responsePayload().get("questionnaireAnswers");
        if (answers instanceof Map<?, ?> answerMap) {
            message.put("questionnaire_answers", answerMap);
        } else {
            message.put("questionnaire_answers", Map.of());
        }
        Object metadata = request.responsePayload().get("metadata");
        if (metadata instanceof Map<?, ?> metadataMap && !metadataMap.isEmpty()) {
            Map<String, Object> metadataCopy = new LinkedHashMap<>();
            metadataMap.forEach((key, value) -> {
                if (key != null) {
                    metadataCopy.put(String.valueOf(key), value);
                }
            });
            Map<String, Object> sanitized = RelayRuntimeWireRequestMapper.sanitizedMetadata(metadataCopy);
            if (!sanitized.isEmpty()) {
                message.put("metadata", sanitized);
            }
        }
        message.put("timestamp", Instant.now().toString());
        return toJson(message);
    }

    private boolean booleanValue(Object value) {
        return value instanceof Boolean bool ? bool : Boolean.parseBoolean(String.valueOf(value));
    }

    private String stringOrDefault(Object value, String defaultValue) {
        if (value == null || String.valueOf(value).isBlank()) {
            return defaultValue;
        }
        return String.valueOf(value);
    }

    private String interruptMessage() {
        return toJson(Map.of("type", "interrupt"));
    }

    private String heartbeatMessage() {
        return toJson(Map.of("type", "heartbeat"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new RelayRuntimeProtocolException("Failed to serialize Relay WebSocket request: " + ex.getMessage());
        }
    }

    private String relaySessionIdForQuery(AgentRuntimeRequest request) {
        if (request.runtimeSessionMode() == RuntimeSessionMode.NEW) {
            return request.sessionId();
        }
        return blank(request.runtimeSessionId()) ? request.sessionId() : request.runtimeSessionId();
    }

    private String relaySessionIdForCancel(AgentRuntimeCancelRequest request) {
        return blank(request.runtimeSessionId()) ? request.sessionId() : request.runtimeSessionId();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private URI endpointUri(AgentRuntimeRequest request) {
        String clientId = request.runId() == null || request.runId().isBlank()
                ? UUID.randomUUID().toString()
                : request.runId();
        return endpointUri(clientId);
    }

    private String interruptClientId(String runId) {
        String prefix = runId == null || runId.isBlank() ? "run" : runId;
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return prefix + "-interrupt-" + suffix;
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
            // Relay config phase has a single release signal. Other initialization frames are isolated here.
            return "session-ready".equals(type);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private RelayRuntimeProtocolException configHandshakeFailure(String frame) {
        if (frame == null || frame.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            if (!"error".equals(type) && !"clear-session".equals(type) && !"session-mismatch".equals(type)
                    && !hasErrorPayload(root)) {
                return null;
            }
            return new RelayRuntimeProtocolException("Relay WebSocket config handshake failed: "
                    + configFailureMessage(root, type));
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private boolean hasErrorPayload(JsonNode root) {
        JsonNode error = root == null ? null : root.get("error");
        return error != null && !error.isNull() && !(error.isTextual() && error.asText("").isBlank());
    }

    private String configFailureMessage(JsonNode root, String type) {
        String message = text(root.path("error_message"));
        if (message == null) {
            message = text(root.path("message"));
        }
        if (message == null) {
            message = text(root.path("reason"));
        }
        if (message == null) {
            message = text(root.path("error_code"));
        }
        if (message == null) {
            JsonNode error = root.get("error");
            if (error != null && error.isObject()) {
                message = text(error.path("message"));
                if (message == null) {
                    message = text(error.path("reason"));
                }
                if (message == null) {
                    message = text(error.path("code"));
                }
            } else if (error != null && !error.isNull()) {
                message = error.asText(null);
            }
        }
        return (type == null || type.isBlank() ? "unknown" : type) + (message == null ? "" : ": " + message);
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

    private boolean heartbeatFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            return "heartbeat".equals(type) || "heartbeat-response".equals(type);
        } catch (JsonProcessingException ex) {
            return false;
        }
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
            return ordinaryTerminalState(state);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private boolean terminalTextFrame(String frame) {
        if (frame == null) {
            return false;
        }
        String normalized = frame.trim().toLowerCase();
        return "[done]".equals(normalized)
                || "done".equals(normalized)
                || "message.completed".equals(normalized)
                || "steam-complete".equals(normalized)
                || "stream-complete".equals(normalized)
                || "stream.complete".equals(normalized)
                || "stream-completed".equals(normalized);
    }

    private boolean interruptPausedAckFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            if (!"session-state".equals(type)) {
                return false;
            }
            String state = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("state")));
            return "paused".equals(state);
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    private boolean ordinaryTerminalState(String state) {
        String normalizedState = RelayRuntimeResponseNormalizer.normalizeTypeName(state);
        return "idle".equals(normalizedState)
                || "completed".equals(normalizedState)
                || "waiting-user-input".equals(normalizedState)
                || "paused".equals(normalizedState);
    }

    private boolean userResponseStartFrame(String frame) {
        if (frame == null || frame.isBlank()) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(frame);
            String type = RelayRuntimeResponseNormalizer.normalizeTypeName(text(root.path("type")));
            if ("session-state".equals(type)) {
                return responseStartingSessionState(text(root.path("state")));
            }
            return switch (type) {
                case "relay-start",
                        "agent",
                        "agent-call",
                        "agent-reasoning",
                        "tool-structured-result",
                        "generate-response",
                        "approval-request",
                        "approval-result",
                        "approval-response" -> true;
                default -> type.startsWith("thinking-") || type.startsWith("tool-");
            };
        } catch (JsonProcessingException ex) {
            /*
             * Relay 普通问答阶段理论上返回 JSON frame。若下游返回纯文本，仍按业务响应处理，避免因为缺少
             * relay-start 而丢失答案；后续 normalizer 会按纯文本 message.delta 处理。
             */
            return true;
        }
    }

    private boolean responseStartingSessionState(String state) {
        String normalizedState = RelayRuntimeResponseNormalizer.normalizeTypeName(state);
        return "waiting-user-input".equals(normalizedState) || "paused".equals(normalizedState);
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

    private Duration interruptAckTimeout() {
        Duration timeout = websocketProperties().getInterruptAckTimeout();
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(5)
                : timeout;
    }

    private Duration maxRunDuration() {
        Duration duration = websocketProperties().getMaxRunDuration();
        return duration == null || duration.isZero() || duration.isNegative()
                ? Duration.ofMinutes(30)
                : duration;
    }

    private Duration heartbeatInterval() {
        Duration interval = websocketProperties().getHeartbeatInterval();
        return interval == null || interval.isZero() || interval.isNegative()
                ? Duration.ofSeconds(20)
                : interval;
    }

    private Duration heartbeatResponseTimeout() {
        Duration timeout = websocketProperties().getHeartbeatResponseTimeout();
        return timeout == null ? Duration.ofSeconds(90) : timeout;
    }

    private Duration livenessCheckInterval(Duration heartbeatResponseTimeout) {
        Duration interval = heartbeatInterval();
        return interval.compareTo(heartbeatResponseTimeout) > 0 ? heartbeatResponseTimeout : interval;
    }

    private long durationToNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ex) {
            return Long.MAX_VALUE;
        }
    }

    private interface ActiveRelayWebSocketExchange {
        void interrupt(String runId);
    }

    private record RunControlContext(ShortRunExchange exchange,
                                     String runId,
                                     AtomicReference<Disposable> heartbeatTimer,
                                     AtomicReference<Disposable> livenessTimer,
                                     AtomicReference<Disposable> maxRunTimer,
                                     AtomicLong lastInboundNanos,
                                     Consumer<Throwable> fail) {
    }

    private final class ShortRunExchange implements ActiveRelayWebSocketExchange {
        private final String runId;
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private ShortRunExchange(String runId) {
            this.runId = runId;
        }

        Flux<String> outbound(String configMessage) {
            return Flux.concat(Mono.just(configMessage), outbound.asFlux());
        }

        void subscription(Disposable disposable) {
            subscription.set(disposable);
        }

        void send(String message) {
            synchronized (this) {
                if (closed.get()) {
                    throw new RelayRuntimeProtocolException("Relay WebSocket short connection is closed. runId=" + runId);
                }
                Sinks.EmitResult result = outbound.tryEmitNext(message);
                if (result.isFailure()) {
                    throw new RelayRuntimeProtocolException("Relay WebSocket outbound emit failed: " + result);
                }
            }
        }

        void completeSending() {
            synchronized (this) {
                outbound.tryEmitComplete();
            }
        }

        @Override
        public void interrupt(String requestedRunId) {
            if (requestedRunId == null || !requestedRunId.equals(runId)) {
                return;
            }
            if (closed.get()) {
                return;
            }
            /*
             * Stop 是 best-effort：先把 Relay interrupt 控制帧送入当前 outbound，再结束本侧发送流。
             * ChatService 的取消正确性仍由 cancel flag、DB guarded insert 与 run.cancelled 事件保证。
             */
            try {
                send(interruptMessage());
            } finally {
                completeSending();
            }
        }

        void close(Throwable cause) {
            Disposable disposable;
            synchronized (this) {
                if (!closed.compareAndSet(false, true)) {
                    return;
                }
                if (cause == null) {
                    outbound.tryEmitComplete();
                } else {
                    outbound.tryEmitError(cause);
                }
                disposable = subscription.getAndSet(null);
            }
            if (disposable != null) {
                disposable.dispose();
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
