package com.huawei.it.ex.one.runtime.infrastructure.relay;

import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeInteractionResponseRequest;
import com.huawei.it.ex.one.runtime.application.model.AgentRuntimeRequest;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/** Owns the existing config-to-business-turn state machine for one Relay short connection. */
final class RelayWebSocketTurnStateMachine {
    private static final AppLogger log = AppLoggerFactory.getLogger(RelayWebSocketRuntimeAdapter.class);

    private final RelayAgentProperties properties;
    private final RelayWebSocketMessageEncoder messageEncoder;
    private final RelayWebSocketFrameClassifier frameClassifier;

    RelayWebSocketTurnStateMachine(RelayAgentProperties properties,
                                   RelayWebSocketMessageEncoder messageEncoder,
                                   RelayWebSocketFrameClassifier frameClassifier) {
        this.properties = properties;
        this.messageEncoder = messageEncoder;
        this.frameClassifier = frameClassifier;
    }

    Exchange exchange(String runId) {
        return new Exchange(runId);
    }

    Flux<String> userMessageFrames(Flux<String> frames, AgentRuntimeRequest request, Exchange exchange) {
        return businessFramesAfterConfig(
                frames, request.runId(), exchange, messageEncoder.userMessage(request));
    }

    Flux<String> interactionResponseFrames(Flux<String> frames,
                                           AgentRuntimeInteractionResponseRequest request,
                                           Exchange exchange) {
        return businessFramesAfterConfig(
                frames, request.runId(), exchange, messageEncoder.approvalResponseMessage(request));
    }

    // Handshake, first-business-frame, terminal and cancellation transitions share one subscription state machine.
    // The exact callback ordering is protocol behavior, so this method is the scoped state-machine exception.
    @SuppressWarnings({"PMD.CognitiveComplexity", "PMD.AvoidDeeplyNestedIfStmts"})
    private Flux<String> businessFramesAfterConfig(Flux<String> frames, String runId,
                                                   Exchange exchange, String initialBusinessMessage) {
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
                disposeAndClear(handshakeTimeout);
                disposeAndClear(heartbeatTimer);
                disposeAndClear(livenessTimer);
                disposeAndClear(maxRunTimer);
                disposeAndClear(frameSubscription);
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
                    RelayRuntimeProtocolException configFailure = frameClassifier.configHandshakeFailure(frame);
                    if (configFailure != null && userMessageReleased.compareAndSet(false, true)) {
                        fail.accept(configFailure);
                        return;
                    }
                    if (frameClassifier.configHandshakeCompleteFrame(frame)
                            && userMessageReleased.compareAndSet(false, true)) {
                        Disposable handshake = handshakeTimeout.getAndSet(null);
                        if (handshake != null) {
                            handshake.dispose();
                        }
                        sink.next(frame);
                        try {
                            exchange.send(initialBusinessMessage);
                            startRunControls(new RunControlContext(
                                    exchange, runId, heartbeatTimer, livenessTimer,
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
                if (frameClassifier.userTurnTerminalFrame(frame)
                        || frameClassifier.terminalTextFrame(frame)) {
                    terminalFrameSeen.set(true);
                }
                sink.next(frame);
            }, fail, () -> {
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
                context.exchange().send(messageEncoder.heartbeatMessage());
            } catch (Throwable ex) {
                context.fail().accept(ex);
            }
        }, context.fail()));
        Duration heartbeatResponseTimeout = heartbeatResponseTimeout();
        if (!heartbeatResponseTimeout.isZero() && !heartbeatResponseTimeout.isNegative()) {
            context.livenessTimer().set(Flux.interval(livenessCheckInterval(heartbeatResponseTimeout))
                    .subscribe(ignored -> {
                        long elapsedNanos = System.nanoTime() - context.lastInboundNanos().get();
                        if (elapsedNanos < durationToNanos(heartbeatResponseTimeout)) {
                            return;
                        }
                        try {
                            context.exchange().interrupt(context.runId());
                        } catch (Throwable ex) {
                            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RELAY_INTERRUPT_FAILED,
                                            "Relay interrupt failed after heartbeat timeout")
                                    .runId(context.runId())
                                    .operation("relay.heartbeat-timeout.interrupt")
                                    .build(), ex);
                        }
                        context.fail().accept(new RelayRuntimeProtocolException(
                                "RELAY_WS_HEARTBEAT_RESPONSE_TIMEOUT: Relay WebSocket heartbeat response timed out. "
                                        + "runId=" + context.runId() + ", timeout=" + heartbeatResponseTimeout));
                    }, context.fail()));
        }
        context.maxRunTimer().set(Mono.delay(maxRunDuration()).subscribe(ignored -> {
            try {
                context.exchange().interrupt(context.runId());
            } catch (Throwable ex) {
                log.warn(SystemErrorLogEntry.builder(SystemErrorCode.RELAY_INTERRUPT_FAILED,
                                "Relay interrupt failed after maximum run duration")
                        .runId(context.runId())
                        .operation("relay.max-duration.interrupt")
                        .build(), ex);
            }
            context.fail().accept(new RelayRuntimeProtocolException(
                    "RELAY_WS_MAX_RUN_DURATION_EXCEEDED: Relay WebSocket run exceeded max duration. runId="
                            + context.runId() + ", maxRunDuration=" + maxRunDuration()));
        }, context.fail()));
    }

    private boolean shouldEmitUserResponseFrame(String frame, AtomicBoolean responseStarted) {
        if (frameClassifier.lateConfigFrame(frame)) {
            return false;
        }
        if (frameClassifier.heartbeatFrame(frame)) {
            return false;
        }
        if (responseStarted.get()) {
            return true;
        }
        if (frameClassifier.userResponseStartFrame(frame)) {
            responseStarted.set(true);
            return true;
        }
        return false;
    }

    private void disposeAndClear(AtomicReference<Disposable> disposableReference) {
        Disposable disposable = disposableReference.getAndSet(null);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    private Duration configHandshakeTimeout() {
        Duration timeout = websocketProperties().getConfigHandshakeTimeout();
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(10)
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

    private RelayAgentProperties.WebSocket websocketProperties() {
        return properties.getRelay().getWebsocket();
    }

    private record RunControlContext(
            Exchange exchange,
            String runId,
            AtomicReference<Disposable> heartbeatTimer,
            AtomicReference<Disposable> livenessTimer,
            AtomicReference<Disposable> maxRunTimer,
            AtomicLong lastInboundNanos,
            Consumer<Throwable> fail
    ) {
    }

    final class Exchange {
        private final String runId;
        private final Sinks.Many<String> outbound = Sinks.many().unicast().onBackpressureBuffer();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final AtomicBoolean closed = new AtomicBoolean(false);

        private Exchange(String runId) {
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
                    throw new RelayRuntimeProtocolException(
                            "Relay WebSocket short connection is closed. runId=" + runId);
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

        void interrupt(String requestedRunId) {
            if (requestedRunId == null || !requestedRunId.equals(runId)) {
                return;
            }
            if (closed.get()) {
                return;
            }
            try {
                send(messageEncoder.stopAllAgentsMessage());
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
}
