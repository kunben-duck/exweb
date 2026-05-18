package com.huawei.finance.front.one.interfaces.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.integration.identity.AuthContextProvider;
import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.application.service.ChatStreamApplicationService;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * 聊天 WebSocket 入口。
 *
 * <p>WebSocket 是用户级长连接，连接身份由 {@link AuthContextProvider} 在服务端解析。
 * 客户端必须先通过 {@code POST /chat/runs} 创建 run，再使用返回的 {@code streamTopicId}
 * 订阅本轮回答。旧的“WebSocket 直接提交聊天请求”模式已移除。</p>
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;
    private final ChatStreamApplicationService chatStreamService;
    private final LocalWebSocketConnectionRegistry connectionRegistry;
    private final ChatEventTranslator eventTranslator;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(AuthContextProvider auth, PermissionChecker permissionChecker,
                                ChatStreamApplicationService chatStreamService,
                                LocalWebSocketConnectionRegistry connectionRegistry,
                                ChatEventTranslator eventTranslator, ObjectMapper objectMapper) {
        this.auth = auth;
        this.permissionChecker = permissionChecker;
        this.chatStreamService = chatStreamService;
        this.connectionRegistry = connectionRegistry;
        this.eventTranslator = eventTranslator;
        this.objectMapper = objectMapper;
    }

    /**
     * 建立并处理一条 WebSocket 连接。
     *
     * @param session WebFlux WebSocket 会话，承载当前物理连接的收发流。
     * @return 连接生命周期完成信号。
     */
    @Override
    public Mono<Void> handle(WebSocketSession session) {
        UserContext user;
        try {
            user = auth.resolve();
            permissionChecker.checkChatPermission(user);
            connectionRegistry.register(session.getId(), user);
        } catch (RuntimeException ex) {
            return session.send(Flux.just(toMessage(session,
                    ChatWebSocketEnvelopeDto.error(null, "WS_AUTH_FAILED", ex.getMessage()))));
        }

        Sinks.Many<WebSocketMessage> outbound = Sinks.many().unicast()
                .onBackpressureBuffer(Queues.<WebSocketMessage>get(256).get());
        Mono<Void> inbound = session.receive()
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                // 当前只接受连接控制消息。聊天请求必须走 POST /chat/runs 创建后台 run。
                .concatMap(message -> handleTextMessage(session, user, outbound, message.getPayloadAsText()))
                .onErrorResume(ex -> {
                    emit(session, outbound, ChatWebSocketEnvelopeDto.error(null, "WS_STREAM_ERROR", ex.getMessage()));
                    return Mono.empty();
                })
                .doFinally(signalType -> {
                    Map<String, Long> acknowledged = connectionRegistry.acknowledgedSubscriptions(session.getId());
                    if (!acknowledged.isEmpty()) {
                        Mono.fromRunnable(() -> chatStreamService.flushAcknowledgements(user, acknowledged))
                                .subscribeOn(Schedulers.boundedElastic())
                                .subscribe(
                                        ignored -> { },
                                        ex -> log.warn("WebSocket ack 游标关闭前刷新失败，connectionId={}, reason={}",
                                                session.getId(), ex.getMessage())
                                );
                    }
                    connectionRegistry.unregister(session.getId());
                    outbound.tryEmitComplete();
                })
                .then();
        return Mono.when(inbound, session.send(outbound.asFlux()));
    }

    private Mono<Void> handleTextMessage(WebSocketSession session, UserContext user,
                                         Sinks.Many<WebSocketMessage> outbound,
                                         String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception ex) {
            emit(session, outbound, ChatWebSocketEnvelopeDto.error(null, "BAD_WS_MESSAGE", ex.getMessage()));
            return Mono.empty();
        }
        String commandId = root.path("id").asText(null);
        if (!root.hasNonNull("type")) {
            emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "WebSocket 仅支持控制消息"));
            return Mono.empty();
        }
        return handleCommandMessage(session, user, outbound, root, commandId);
    }

    private Mono<Void> handleCommandMessage(WebSocketSession session, UserContext user,
                                            Sinks.Many<WebSocketMessage> outbound,
                                            JsonNode root, String commandId) {
        String type = root.path("type").asText("");
        if ("connect".equals(type)) {
            String presence = presenceState(root.path("presence"));
            connectionRegistry.updatePresence(session.getId(), presence);
            emit(session, outbound, ChatWebSocketEnvelopeDto.reply(commandId,
                    Map.of("type", "connect", "connectionId", session.getId(), "presence", presence)));
            return Mono.empty();
        }
        if ("presence".equals(type)) {
            String state = root.path("state").asText("foreground");
            connectionRegistry.updatePresence(session.getId(), state);
            emit(session, outbound, ChatWebSocketEnvelopeDto.reply(commandId, Map.of("type", "presence", "state", state)));
            return Mono.empty();
        }
        if ("subscribe".equals(type)) {
            return subscribe(session, user, outbound, root, commandId);
        }
        if ("unsubscribe".equals(type)) {
            String topicId = root.path("topicId").asText(null);
            if (topicId == null || topicId.isBlank()) {
                emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "topicId 不能为空"));
                return Mono.empty();
            }
            connectionRegistry.unsubscribe(session.getId(), topicId);
            emit(session, outbound, ChatWebSocketEnvelopeDto.reply(commandId, Map.of("type", "unsubscribe", "topicId", topicId)));
            return Mono.empty();
        }
        if ("ack".equals(type)) {
            String topicId = root.path("topicId").asText(null);
            long seq = root.path("seq").asLong(0);
            if (topicId == null || topicId.isBlank()) {
                emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "topicId 不能为空"));
                return Mono.empty();
            }
            if (!connectionRegistry.ack(session.getId(), topicId, seq)) {
                emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "NOT_SUBSCRIBED",
                        "当前连接未订阅 topic: " + topicId));
                return Mono.empty();
            }
            return Mono.<Void>fromRunnable(() -> chatStreamService.acknowledgeRunTopic(user, topicId, seq))
                    .subscribeOn(Schedulers.boundedElastic())
                    .doOnSuccess(ignored -> emit(session, outbound,
                            ChatWebSocketEnvelopeDto.reply(commandId, Map.of("type", "ack", "topicId", topicId, "seq", seq))))
                    .onErrorResume(ex -> {
                        emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "ACK_ERROR", ex.getMessage()));
                        return Mono.<Void>empty();
                    });
        }
        emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE",
                "不支持的 WebSocket command type: " + type));
        return Mono.empty();
    }

    private Mono<Void> subscribe(WebSocketSession session, UserContext user, Sinks.Many<WebSocketMessage> outbound,
                                 JsonNode root, String commandId) {
        String topicId = root.path("topicId").asText(null);
        long afterSeq = root.path("afterSeq").asLong(0);
        if (topicId == null || topicId.isBlank()) {
            emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "topicId 不能为空"));
            return Mono.empty();
        }
        return Mono.fromCallable(() -> chatStreamService.ensureRunTopicAccessible(user, topicId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ignored -> {
                    if (connectionRegistry.get(session.getId()).isEmpty()) {
                        return Mono.<Void>empty();
                    }
                    emit(session, outbound, ChatWebSocketEnvelopeDto.reply(commandId,
                            Map.of("type", "subscribe", "topicId", topicId, "recovered", afterSeq > 0, "lastSeq", afterSeq)));
                    Sinks.Empty<Void> cancellation = Sinks.empty();
                    connectionRegistry.subscribe(session.getId(), topicId, afterSeq, cancellationDisposable(cancellation));
                    chatStreamService.resumeRunTopic(user, topicId, afterSeq)
                            .map(eventTranslator::toDto)
                            .takeUntilOther(cancellation.asMono())
                            .subscribe(
                                    dto -> emitTopicEvent(session, outbound, topicId, cancellation, dto),
                                    ex -> {
                                        emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId,
                                                "SUBSCRIBE_ERROR", ex.getMessage()));
                                        connectionRegistry.unsubscribe(session.getId(), topicId);
                                    }
                            );
                    return Mono.<Void>empty();
                })
                .onErrorResume(ex -> {
                    emit(session, outbound, ChatWebSocketEnvelopeDto.error(commandId, "SUBSCRIBE_ERROR", ex.getMessage()));
                    return Mono.<Void>empty();
                });
    }

    private void emitTopicEvent(WebSocketSession session, Sinks.Many<WebSocketMessage> outbound, String topicId,
                                Sinks.Empty<Void> cancellation, ChatEventDto dto) {
        if (dto == null) {
            return;
        }
        LocalWebSocketConnectionRegistry.DeliveryDecision decision =
                connectionRegistry.markDelivered(session.getId(), topicId, dto.sequence());
        if (decision.action() == LocalWebSocketConnectionRegistry.Action.DELIVER) {
            emit(session, outbound, ChatWebSocketEnvelopeDto.message(topicId, dto));
        } else if (decision.action() == LocalWebSocketConnectionRegistry.Action.RECOVER_REQUIRED) {
            emit(session, outbound, ChatWebSocketEnvelopeDto.recoverRequired(topicId, decision.lastAckSeq(), decision.actualSeq()));
            // 一旦发现乱序或缺口，先把 recover-required 发给前端，再暂停该 topic，避免继续推送更高 seq。
            cancellation.tryEmitEmpty();
            connectionRegistry.unsubscribe(session.getId(), topicId);
        }
    }

    private Disposable cancellationDisposable(Sinks.Empty<Void> cancellation) {
        return new Disposable() {
            private volatile boolean disposed;

            @Override
            public void dispose() {
                disposed = true;
                cancellation.tryEmitEmpty();
            }

            @Override
            public boolean isDisposed() {
                return disposed;
            }
        };
    }

    private void emit(WebSocketSession session, Sinks.Many<WebSocketMessage> outbound, ChatWebSocketEnvelopeDto dto) {
        Sinks.EmitResult result = outbound.tryEmitNext(toMessage(session, dto));
        if (result.isFailure()) {
            connectionRegistry.unregister(session.getId());
            outbound.tryEmitComplete();
            session.close(CloseStatus.SERVICE_OVERLOAD).subscribe();
        }
    }

    private WebSocketMessage toMessage(WebSocketSession session, ChatWebSocketEnvelopeDto dto) {
        try {
            return session.textMessage(objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WebSocket 响应序列化失败", ex);
        }
    }

    private String presenceState(JsonNode presenceNode) {
        if (presenceNode == null || presenceNode.isMissingNode() || presenceNode.isNull()) {
            return "foreground";
        }
        if (presenceNode.isObject()) {
            return presenceNode.path("state").asText("foreground");
        }
        String value = presenceNode.asText("foreground");
        return value == null || value.isBlank() ? "foreground" : value;
    }
}
