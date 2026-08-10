package com.huawei.it.ex.one.infrastructure.redis;

import com.huawei.it.ex.one.application.integration.conversation.RunStopControlBus;
import com.huawei.it.ex.one.application.integration.identity.ApplicationInstanceIdProvider;
import com.huawei.it.ex.one.common.error.SystemErrorCode;
import com.huawei.it.ex.one.common.error.SystemErrorLogEntry;
import com.huawei.it.ex.one.common.logging.AppLogger;
import com.huawei.it.ex.one.common.logging.AppLoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 基于每实例固定Redis Pub/Sub频道实现的ChatRun stop控制总线。 */
@Component
public class RedisRunStopControlBus implements RunStopControlBus, MessageListener {
    private static final AppLogger log = AppLoggerFactory.getLogger(RedisRunStopControlBus.class);
    private static final int MAX_RESPONSES_PER_REQUEST = 4;

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final FinanceExRedisKeyBuilder redisKeys;
    private final ApplicationInstanceIdProvider instanceIdProvider;
    private final RedisMessageListenerContainer listenerContainer;
    private final ConcurrentHashMap<String, Sinks.Many<Response>> pending = new ConcurrentHashMap<>();
    private final AtomicReference<Consumer<Request>> handler = new AtomicReference<>();

    public RedisRunStopControlBus(StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  FinanceExRedisKeyBuilder redisKeys,
                                  ApplicationInstanceIdProvider instanceIdProvider,
                                  RedisConnectionFactory connectionFactory) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisKeys = redisKeys;
        this.instanceIdProvider = instanceIdProvider;
        this.listenerContainer = new RedisMessageListenerContainer();
        this.listenerContainer.setConnectionFactory(connectionFactory);
        // Listener只做小消息解析并把任务交给有界业务Scheduler，避免默认执行器为控制消息扩张线程。
        this.listenerContainer.setTaskExecutor(Runnable::run);
    }

    @PostConstruct
    public void start() {
        String channel = redisKeys.runStopControlChannel(instanceIdProvider.currentInstanceId());
        try {
            listenerContainer.afterPropertiesSet();
            listenerContainer.addMessageListener(this, new ChannelTopic(channel));
            listenerContainer.start();
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                            "Run stop control listener failed to start")
                    .operation("chat-run.stop-control.start")
                    .attribute("instanceId", instanceIdProvider.currentInstanceId())
                    .build(), ex);
        }
    }

    @PreDestroy
    public void stop() {
        pending.values().forEach(sink -> sink.tryEmitComplete());
        pending.clear();
        try {
            listenerContainer.stop();
            listenerContainer.destroy();
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_UNAVAILABLE,
                            "Run stop control listener failed to stop cleanly")
                    .operation("chat-run.stop-control.stop")
                    .build(), ex);
        }
    }

    @Override
    public Delivery send(Request request) {
        if (request == null || blank(request.requestId()) || blank(request.ownerInstanceId())) {
            return new Delivery(0L, Flux.just(unavailable(request, "invalid stop control request")));
        }
        Queue<Response> queue = new ArrayBlockingQueue<>(MAX_RESPONSES_PER_REQUEST);
        Sinks.Many<Response> sink = Sinks.many().unicast().onBackpressureBuffer(queue);
        Sinks.Many<Response> previous = pending.putIfAbsent(request.requestId(), sink);
        if (previous != null) {
            return new Delivery(0L, Flux.just(unavailable(request, "duplicate stop request id")));
        }
        long subscribers;
        try {
            Long result = redis.convertAndSend(
                    redisKeys.runStopControlChannel(request.ownerInstanceId()),
                    objectMapper.writeValueAsString(StopRequestWire.from(request)));
            subscribers = result == null ? 0L : Math.max(0L, result);
        } catch (Exception ex) {
            subscribers = 0L;
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                            "Run stop control request publish failed")
                    .runId(request.runId())
                    .operation("chat-run.stop-control.publish-request")
                    .attribute("ownerInstanceId", request.ownerInstanceId())
                    .build(), ex);
        }
        if (subscribers == 0L) {
            emitResponse(sink, unavailable(request, "owner control channel has no subscriber"));
        }
        Flux<Response> responses = sink.asFlux()
                .doFinally(ignored -> pending.remove(request.requestId(), sink));
        return new Delivery(subscribers, responses);
    }

    @Override
    public void registerHandler(Consumer<Request> nextHandler) {
        if (nextHandler == null || !handler.compareAndSet(null, nextHandler)) {
            throw new IllegalStateException("Run stop control handler must be registered exactly once");
        }
    }

    @Override
    public void respond(Response response) {
        if (response == null || blank(response.requestId()) || blank(response.requesterInstanceId())) {
            return;
        }
        try {
            redis.convertAndSend(
                    redisKeys.runStopControlChannel(response.requesterInstanceId()),
                    objectMapper.writeValueAsString(StopResponseWire.from(response)));
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_PUBLISH_FAILED,
                            "Run stop control response publish failed")
                    .runId(response.runId())
                    .operation("chat-run.stop-control.publish-response")
                    .attribute("requestId", response.requestId())
                    .attribute("status", response.status())
                    .build(), ex);
        }
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        if (message == null || message.getBody() == null) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(
                    new String(message.getBody(), StandardCharsets.UTF_8));
            String type = root == null ? null : root.path("type").asText(null);
            if ("STOP_REQUEST".equals(type)) {
                handleRequest(objectMapper.treeToValue(root, StopRequestWire.class).toDomain());
            } else if ("STOP_RESPONSE".equals(type)) {
                handleResponse(objectMapper.treeToValue(root, StopResponseWire.class).toDomain());
            }
        } catch (Exception ex) {
            log.warn(SystemErrorLogEntry.builder(SystemErrorCode.REDIS_SUBSCRIBE_FAILED,
                            "Run stop control message could not be decoded")
                    .operation("chat-run.stop-control.consume")
                    .build(), ex);
        }
    }

    private void handleRequest(Request request) {
        if (request == null
                || !instanceIdProvider.currentInstanceId().equals(request.ownerInstanceId())) {
            return;
        }
        Consumer<Request> current = handler.get();
        if (current == null) {
            respond(new Response(request.requestId(), request.runId(), request.requesterInstanceId(),
                    request.ownerInstanceId(), Status.UNAVAILABLE, null,
                    "owner stop handler is unavailable"));
            return;
        }
        current.accept(request);
    }

    private void handleResponse(Response response) {
        if (response == null
                || !instanceIdProvider.currentInstanceId().equals(response.requesterInstanceId())) {
            return;
        }
        Sinks.Many<Response> sink = pending.get(response.requestId());
        if (sink != null) {
            emitResponse(sink, response);
        }
    }

    private void emitResponse(Sinks.Many<Response> sink, Response response) {
        if (sink == null || response == null) {
            return;
        }
        Sinks.EmitResult result = sink.tryEmitNext(response);
        if (response.terminal() || result.isFailure()) {
            sink.tryEmitComplete();
        }
    }

    private Response unavailable(Request request, String message) {
        return new Response(
                request == null ? null : request.requestId(),
                request == null ? null : request.runId(),
                request == null ? instanceIdProvider.currentInstanceId() : request.requesterInstanceId(),
                request == null ? null : request.ownerInstanceId(),
                Status.UNAVAILABLE,
                null,
                message);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record StopRequestWire(
            String type,
            String requestId,
            String runId,
            String requesterInstanceId,
            String ownerInstanceId,
            long fencingToken,
            String reason
    ) {
        private static StopRequestWire from(Request request) {
            return new StopRequestWire(
                    "STOP_REQUEST", request.requestId(), request.runId(), request.requesterInstanceId(),
                    request.ownerInstanceId(), request.fencingToken(), request.reason());
        }

        private Request toDomain() {
            return new Request(
                    requestId, runId, requesterInstanceId, ownerInstanceId, fencingToken, reason);
        }
    }

    private record StopResponseWire(
            String type,
            String requestId,
            String runId,
            String requesterInstanceId,
            String ownerInstanceId,
            Status status,
            String runStatus,
            String message
    ) {
        private static StopResponseWire from(Response response) {
            return new StopResponseWire(
                    "STOP_RESPONSE", response.requestId(), response.runId(), response.requesterInstanceId(),
                    response.ownerInstanceId(), response.status(), response.runStatus(), response.message());
        }

        private Response toDomain() {
            return new Response(
                    requestId, runId, requesterInstanceId, ownerInstanceId, status, runStatus, message);
        }
    }
}
