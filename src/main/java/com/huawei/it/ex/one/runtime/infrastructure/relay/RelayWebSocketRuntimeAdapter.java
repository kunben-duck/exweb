package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.common.http.AgentRuntimeForwardCookieProperties;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeCancelRequest;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRequest;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.trace.TraceContext;
import com.huawei.it.ex.one.common.event.ChatEvent;
import com.huawei.it.ex.one.common.event.MessageCompletedEvent;
import java.net.URI;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import reactor.core.Exceptions;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Relay WebSocket 协议实现。
 *
 * <p>每个 ChatService run 都建立一条短生命周期下游 WebSocket，先完成 {@code config} 阶段，再发送
 * {@code user-message} 或 {@code approval-response}。配置阶段 frame 只用于握手判定，不进入 ChatService
 * 标准事件流；业务阶段 frame 复用 {@link RelayRuntimeResponseNormalizer} 转为标准事件。</p>
 */
@Component
@EnableConfigurationProperties({RelayAgentProperties.class, AgentRuntimeForwardCookieProperties.class})
@ConditionalOnProperty(prefix = "financeex.agent-runtime.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RelayWebSocketRuntimeAdapter implements RelayRuntimeProtocolAdapter {
    private static final AppLogger log = AppLoggerFactory.getLogger(RelayWebSocketRuntimeAdapter.class);

    private final RelayRuntimeResponseNormalizer responseNormalizer;
    private final WebSocketClient webSocketClient;
    private final RelayWebSocketConnectionSupport connectionSupport;
    private final RelayWebSocketMessageEncoder messageEncoder;
    private final RelayWebSocketFrameClassifier frameClassifier;
    private final RelayWebSocketTurnStateMachine turnStateMachine;
    /** Active run -> outbound exchange, used only for best-effort Relay WS interrupt on stop/delete. */
    private final ConcurrentHashMap<String, RelayWebSocketTurnStateMachine.Exchange> activeExchanges =
            new ConcurrentHashMap<>();

    @Autowired
    public RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                        RelayAgentProperties properties,
                                        AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                        RelayRuntimeResponseNormalizer responseNormalizer) {
        this(objectMapper, properties, forwardCookieProperties, responseNormalizer,
                RelayWebSocketConnectionSupport.webSocketClient(properties));
    }

    RelayWebSocketRuntimeAdapter(ObjectMapper objectMapper,
                                 RelayAgentProperties properties,
                                 AgentRuntimeForwardCookieProperties forwardCookieProperties,
                                 RelayRuntimeResponseNormalizer responseNormalizer,
                                 WebSocketClient webSocketClient) {
        this.responseNormalizer = responseNormalizer;
        this.webSocketClient = webSocketClient;
        this.connectionSupport = new RelayWebSocketConnectionSupport(properties, forwardCookieProperties);
        this.messageEncoder = new RelayWebSocketMessageEncoder(objectMapper, properties);
        this.frameClassifier = new RelayWebSocketFrameClassifier(objectMapper);
        this.turnStateMachine = new RelayWebSocketTurnStateMachine(
                properties, messageEncoder, frameClassifier);
    }

    @Override
    public Flux<ChatEvent> query(AgentRuntimeRequest request) {
        AtomicBoolean messageCompleted = new AtomicBoolean(false);
        Flux<ChatEvent> events = queryWithShortConnection(request, messageCompleted);

        return events.concatWith(Mono.defer(() -> messageCompleted.get()
                        ? Mono.empty()
                        : Mono.just(MessageCompletedEvent.of(request.runId(), request.sessionId()))))
                .doOnError(ex -> logRelayFailure(
                        request.runId(), request.sessionId(), request.traceContext(), "relay.query", ex));
    }

    @Override
    public boolean supportsUserResponseContinuation() {
        return true;
    }

    @Override
    public Flux<ChatEvent> continueWithUserResponse(AgentRuntimeInteractionResponseRequest request) {
        AtomicBoolean messageCompleted = new AtomicBoolean(false);
        Flux<ChatEvent> events = interactionWithShortConnection(request, messageCompleted);
        return events.concatWith(Mono.defer(() -> messageCompleted.get()
                        ? Mono.empty()
                        : Mono.just(MessageCompletedEvent.of(request.runId(), request.sessionId()))))
                .doOnError(ex -> logRelayFailure(
                        request.runId(), request.sessionId(), request.traceContext(), "relay.interaction", ex));
    }

    private void logRelayFailure(String runId, String sessionId, TraceContext traceContext,
                                 String operation, Throwable failure) {
        log.warn(SystemErrorLogEntry.builder(classifyRelayFailure(failure), "Relay WebSocket operation failed")
                .traceId(traceContext == null ? null : traceContext.traceId())
                .runId(runId)
                .sessionId(sessionId)
                .operation(operation)
                .build(), failure);
    }

    private SystemErrorCode classifyRelayFailure(Throwable failure) {
        Throwable cause = Exceptions.unwrap(failure);
        String message = cause == null || cause.getMessage() == null
                ? ""
                : cause.getMessage().toLowerCase(Locale.ROOT);
        String className = cause == null ? "" : cause.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (timeoutFailure(cause, message)) {
            return classifyTimeout(message, className);
        }
        if (cause instanceof RelayRuntimeSessionUnavailableException) {
            return SystemErrorCode.RELAY_SESSION_UNAVAILABLE;
        }
        if (message.contains("closed")) {
            return SystemErrorCode.RELAY_UNEXPECTED_CLOSED;
        }
        if (message.contains("handshake")) {
            return SystemErrorCode.RELAY_CONFIG_HANDSHAKE_FAILED;
        }
        if (message.contains("serialize") || message.contains("outbound") || message.contains("emit failed")) {
            return SystemErrorCode.RELAY_OUTBOUND_FAILED;
        }
        if (cause instanceof RelayRuntimeProtocolException) {
            return SystemErrorCode.RELAY_PROTOCOL_INVALID;
        }
        return SystemErrorCode.RELAY_ERROR;
    }

    private boolean timeoutFailure(Throwable cause, String message) {
        return cause instanceof TimeoutException || message.contains("timeout") || message.contains("timed out");
    }

    private SystemErrorCode classifyTimeout(String message, String className) {
        if (message.contains("heartbeat")) {
            return SystemErrorCode.RELAY_HEARTBEAT_TIMEOUT;
        }
        if (message.contains("max run") || message.contains("max_run") || message.contains("run duration")) {
            return SystemErrorCode.RELAY_RUN_TIMEOUT;
        }
        if (className.contains("connect") || message.contains("connect timeout")) {
            return SystemErrorCode.RELAY_CONNECT_TIMEOUT;
        }
        return SystemErrorCode.RELAY_CONFIG_TIMEOUT;
    }

    private Flux<ChatEvent> queryWithShortConnection(AgentRuntimeRequest request, AtomicBoolean messageCompleted) {
        return Flux.create(sink -> {
            RelayWebSocketTurnStateMachine.Exchange exchange = turnStateMachine.exchange(request.runId());
            registerActiveExchange(request.runId(), exchange);
            var subscription = executeWithOpeningHandshakeTimeout(
                    connectionSupport.endpointUri(request),
                    connectionSupport.outboundHeaders(request.forwardHeaders()), request.runId(),
                    session -> {
                        Mono<Void> outbound = session.send(exchange.outbound(messageEncoder.configMessage(request))
                                .map(session::textMessage));
                        Flux<String> frames = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frame -> connectionSupport.validateFrameSize(frame, request.runId()));
                        Flux<ChatEvent> normalized = turnStateMachine.userMessageFrames(frames, request, exchange)
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

    private Flux<ChatEvent> interactionWithShortConnection(AgentRuntimeInteractionResponseRequest request,
                                                    AtomicBoolean messageCompleted) {
        return Flux.create(sink -> {
            RelayWebSocketTurnStateMachine.Exchange exchange = turnStateMachine.exchange(request.runId());
            registerActiveExchange(request.runId(), exchange);
            var subscription = executeWithOpeningHandshakeTimeout(
                    connectionSupport.endpointUri(request.runId()),
                    connectionSupport.outboundHeaders(request.forwardHeaders()), request.runId(),
                    session -> {
                        Mono<Void> outbound = session.send(exchange.outbound(messageEncoder.configMessage(request))
                                .map(session::textMessage));
                        Flux<String> frames = session.receive()
                                .map(WebSocketMessage::getPayloadAsText)
                                .doOnNext(frame -> connectionSupport.validateFrameSize(frame, request.runId()));
                        Flux<ChatEvent> normalized = turnStateMachine.interactionResponseFrames(
                                        frames, request, exchange)
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
                .takeUntil(frameClassifier::userTurnTerminalFrame)
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
                    RelayWebSocketTurnStateMachine.Exchange exchange = activeExchanges.remove(request.runId());
                    if (exchange != null) {
                        log.info("Relay WebSocket interrupt uses active exchange. runId={}", request.runId());
                        return Mono.fromRunnable(() -> exchange.interrupt(request.runId()));
                    }
                    return interruptViaResumeConnection(request);
                })
                .onErrorResume(ex -> {
                    String runId = request == null ? null : request.runId();
                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RELAY_INTERRUPT_FAILED,
                                    "Relay WebSocket interrupt failed")
                            .runId(runId)
                            .operation("relay.interrupt")
                            .build(), ex);
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
        String clientId = connectionSupport.interruptClientId(request.runId());
        String relaySessionId = messageEncoder.relaySessionIdForCancel(request);
        return executeWithOpeningHandshakeTimeout(
                connectionSupport.endpointUri(clientId),
                connectionSupport.outboundHeaders(request.forwardHeaders()), request.runId(), session -> {
            Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
            Mono<Void> outboundSend = session.send(Flux.concat(
                            Mono.just(messageEncoder.configMessage(request)), outbound.asFlux())
                    .map(session::textMessage));
            Flux<String> frames = session.receive()
                    .map(WebSocketMessage::getPayloadAsText)
                    .doOnNext(frame -> connectionSupport.validateFrameSize(frame, request.runId()))
                    .timeout(connectionSupport.websocketProperties().getIdleTimeout());
            Mono<Void> releaseInterrupt = waitForInterruptPausedAck(frames, outbound, request, clientId, relaySessionId)
                    .doFinally(signal -> outbound.tryEmitComplete())
                    .then(session.close())
                    .onErrorResume(error -> session.close().then(Mono.error(error)));
            log.info("Relay WebSocket interrupt opens temporary resume connection. runId={}, runtimeSessionId={}, clientId={}",
                    request.runId(), relaySessionId, clientId);
            return Mono.when(outboundSend, releaseInterrupt);
        });
    }

    private Mono<Void> executeWithOpeningHandshakeTimeout(URI uri, HttpHeaders headers, String runId,
                                                           WebSocketHandler handler) {
        return Mono.defer(() -> {
            Sinks.One<Void> openingHandshakeCompleted = Sinks.one();
            Mono<Void> execution = webSocketClient.execute(uri, headers, session -> {
                openingHandshakeCompleted.tryEmitEmpty();
                return handler.handle(session);
            });
            // Upgrade 成功后 guard 转为静默等待，不能先完成并取消已经建立的业务 WebSocket。
            Mono<Void> openingHandshakeGuard = openingHandshakeCompleted.asMono()
                    .timeout(connectionSupport.configHandshakeTimeout())
                    .onErrorMap(TimeoutException.class, ignored -> new RelayRuntimeProtocolException(
                            "RELAY_WS_CONFIG_TIMEOUT: Relay WebSocket opening handshake timed out. "
                                    + "stage=opening-handshake, runId=" + runId))
                    .then(Mono.never());
            return Mono.firstWithSignal(execution, openingHandshakeGuard);
        });
    }

    // Config-ready, interrupt-sent and paused-ack transitions are one ordered WebSocket protocol state machine.
    // Keeping the callbacks together preserves timer disposal and exactly-once completion behavior.
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.AvoidDeeplyNestedIfStmts"})
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
            configTimeout.set(Mono.delay(connectionSupport.configHandshakeTimeout()).subscribe(ignored -> fail.accept(
                    new RelayRuntimeProtocolException("RELAY_WS_CONFIG_TIMEOUT: Relay WebSocket interrupt config "
                            + "handshake timed out. runId=" + request.runId())), fail));
            frameSubscription.set(frames.subscribe(frame -> {
                if (done.get()) {
                    return;
                }
                try {
                    if (!configReady.get()) {
                        RelayRuntimeProtocolException configFailure = frameClassifier.configHandshakeFailure(frame);
                        if (configFailure != null) {
                            fail.accept(configFailure);
                            return;
                        }
                        if (frameClassifier.configHandshakeCompleteFrame(frame)) {
                            configReady.set(true);
                            Disposable configTimer = configTimeout.getAndSet(null);
                            if (configTimer != null) {
                                configTimer.dispose();
                            }
                            emitInterrupt(outbound, request.runId());
                            interruptSent.set(true);
                            log.info("Relay WebSocket interrupt sent. runId={}, runtimeSessionId={}, clientId={}, interruptSent=true",
                                    request.runId(), relaySessionId, clientId);
                            ackTimeout.set(Mono.delay(connectionSupport.interruptAckTimeout()).subscribe(ignored -> {
                                if (done.compareAndSet(false, true)) {
                                    cleanup.run();
                                    log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RELAY_INTERRUPT_FAILED,
                                                    "Relay WebSocket interrupt acknowledgement timed out")
                                            .runId(request.runId())
                                            .sessionId(request.sessionId())
                                            .operation("relay.interrupt.ack")
                                            .attribute("runtimeSessionId", relaySessionId)
                                            .attribute("clientId", clientId)
                                            .build());
                                    sink.success();
                                }
                            }, fail));
                        }
                        return;
                    }
                    RelayRuntimeProtocolException failure = frameClassifier.configHandshakeFailure(frame);
                    if (failure != null) {
                        fail.accept(failure);
                        return;
                    }
                    if (frameClassifier.interruptPausedAckFrame(frame)) {
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
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RELAY_UNEXPECTED_CLOSED,
                                "Relay WebSocket interrupt connection closed before acknowledgement")
                        .runId(request.runId())
                        .sessionId(request.sessionId())
                        .operation("relay.interrupt.ack")
                        .attribute("runtimeSessionId", relaySessionId)
                        .attribute("clientId", clientId)
                        .attribute("interruptSent", interruptSent.get())
                        .build());
                complete.run();
            }));
            sink.onDispose(cleanup::run);
        });
    }

    private void emitInterrupt(Sinks.Many<String> outbound, String runId) {
        Sinks.EmitResult result = outbound.tryEmitNext(messageEncoder.stopAllAgentsMessage());
        if (result.isFailure()) {
            throw new RelayRuntimeProtocolException("Relay WebSocket interrupt outbound emit failed. runId="
                    + runId + ", result=" + result);
        }
        outbound.tryEmitComplete();
    }

    private void registerActiveExchange(String runId, RelayWebSocketTurnStateMachine.Exchange exchange) {
        if (runId == null || runId.isBlank() || exchange == null) {
            return;
        }
        activeExchanges.put(runId, exchange);
    }

    private void emitEvent(AtomicBoolean messageCompleted, ChatEvent event) {
        if ("message.completed".equals(event.type())) {
            messageCompleted.set(true);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
