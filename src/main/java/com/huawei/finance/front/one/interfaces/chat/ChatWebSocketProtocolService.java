package com.huawei.finance.front.one.interfaces.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.service.ChatStreamApplicationService;
import com.huawei.finance.front.one.application.service.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

/**
 * 前端 WebSocket 协议服务。
 *
 * <p>该服务承载 FinanceEX 前端 WebSocket 的业务协议：连接鉴权、presence、run topic
 * 订阅、ack 游标、乱序恢复提示和订阅释放。它不依赖 WebFlux 或 Servlet 具体连接类型，
 * 因此同一套协议可同时被 {@link ChatWebSocketHandler} 和 {@link ChatServletWebSocketHandler}
 * 复用。</p>
 */
@Component
public class ChatWebSocketProtocolService {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketProtocolService.class);

    private final PermissionChecker permissionChecker;
    private final ChatStreamApplicationService chatStreamService;
    private final LocalWebSocketConnectionRegistry connectionRegistry;
    private final ChatEventTranslator eventTranslator;
    private final ObjectMapper objectMapper;

    public ChatWebSocketProtocolService(PermissionChecker permissionChecker,
                                        ChatStreamApplicationService chatStreamService,
                                        LocalWebSocketConnectionRegistry connectionRegistry,
                                        ChatEventTranslator eventTranslator, ObjectMapper objectMapper) {
        this.permissionChecker = permissionChecker;
        this.chatStreamService = chatStreamService;
        this.connectionRegistry = connectionRegistry;
        this.eventTranslator = eventTranslator;
        this.objectMapper = objectMapper;
    }

    /**
     * 注册已经完成入口鉴权的 WebSocket 连接。
     *
     * <p>该方法不得读取 {@code AuthContextProvider} 或企业 ThreadLocal。MVC/Servlet 模式下，
     * 用户身份必须由 handshake interceptor 在 HTTP upgrade 前解析并固化；WebFlux 模式下，
     * 也必须由入口 handler 解析后显式传入。</p>
     *
     * @param connectionId 当前物理 WebSocket 连接 ID。
     * @param user 入口阶段解析出的用户身份快照。
     * @return 当前连接的用户身份快照。
     */
    public UserContext open(String connectionId, UserContext user) {
        if (user == null) {
            throw new SecurityException("WebSocket 用户身份缺失");
        }
        permissionChecker.checkChatPermission(user);
        connectionRegistry.register(connectionId, user);
        return user;
    }

    /**
     * 关闭 WebSocket 连接并释放全部订阅。
     *
     * @param connectionId 当前物理 WebSocket 连接 ID。
     * @param user 连接建立时解析出的用户身份快照。
     */
    public void close(String connectionId, UserContext user) {
        Map<String, Long> acknowledged = connectionRegistry.acknowledgedSubscriptions(connectionId);
        if (user != null && !acknowledged.isEmpty()) {
            Mono.fromRunnable(() -> chatStreamService.flushAcknowledgements(user, acknowledged))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            ignored -> { },
                            ex -> log.warn("WebSocket ack 游标关闭前刷新失败，connectionId={}, reason={}",
                                    connectionId, ex.getMessage())
                    );
        }
        connectionRegistry.unregister(connectionId);
    }

    /**
     * 处理一帧客户端文本控制消息。
     *
     * @param connectionId 当前物理 WebSocket 连接 ID。
     * @param user 连接建立时解析出的用户身份快照。
     * @param outbound 出站 envelope 通道。
     * @param payload 客户端文本帧。
     * @return 当前控制消息处理完成信号。
     */
    public Mono<Void> handleTextMessage(String connectionId, UserContext user,
                                        ChatWebSocketOutbound outbound, String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception ex) {
            outbound.emit(ChatWebSocketEnvelopeDto.error(null, "BAD_WS_MESSAGE", ex.getMessage()));
            return Mono.empty();
        }
        String commandId = root.path("id").asText(null);
        if (!root.hasNonNull("type")) {
            outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "WebSocket 仅支持控制消息"));
            return Mono.empty();
        }
        return handleCommandMessage(connectionId, user, outbound, root, commandId);
    }

    private Mono<Void> handleCommandMessage(String connectionId, UserContext user,
                                            ChatWebSocketOutbound outbound,
                                            JsonNode root, String commandId) {
        String type = root.path("type").asText("");
        if ("connect".equals(type)) {
            String presence = presenceState(root.path("presence"));
            connectionRegistry.updatePresence(connectionId, presence);
            outbound.emit(ChatWebSocketEnvelopeDto.reply(commandId,
                    Map.of("type", "connect", "connectionId", connectionId, "presence", presence)));
            return Mono.empty();
        }
        if ("presence".equals(type)) {
            String state = root.path("state").asText("foreground");
            connectionRegistry.updatePresence(connectionId, state);
            outbound.emit(ChatWebSocketEnvelopeDto.reply(commandId, Map.of("type", "presence", "state", state)));
            return Mono.empty();
        }
        if ("subscribe".equals(type)) {
            return subscribe(connectionId, user, outbound, root, commandId);
        }
        if ("unsubscribe".equals(type)) {
            String topicId = root.path("topicId").asText(null);
            if (topicId == null || topicId.isBlank()) {
                outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "topicId 不能为空"));
                return Mono.empty();
            }
            connectionRegistry.unsubscribe(connectionId, topicId);
            outbound.emit(ChatWebSocketEnvelopeDto.reply(commandId, Map.of("type", "unsubscribe", "topicId", topicId)));
            return Mono.empty();
        }
        if ("ack".equals(type)) {
            return acknowledge(connectionId, user, outbound, root, commandId);
        }
        outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE",
                "不支持的 WebSocket command type: " + type));
        return Mono.empty();
    }

    private Mono<Void> acknowledge(String connectionId, UserContext user, ChatWebSocketOutbound outbound,
                                   JsonNode root, String commandId) {
        String topicId = root.path("topicId").asText(null);
        long seq = root.path("seq").asLong(0);
        if (topicId == null || topicId.isBlank()) {
            outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "topicId 不能为空"));
            return Mono.empty();
        }
        if (!connectionRegistry.ack(connectionId, topicId, seq)) {
            outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "NOT_SUBSCRIBED",
                    "当前连接未订阅 topic: " + topicId));
            return Mono.empty();
        }
        return Mono.<Void>fromRunnable(() -> chatStreamService.acknowledgeRunTopic(user, topicId, seq))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(ignored -> outbound.emit(ChatWebSocketEnvelopeDto.reply(commandId,
                        Map.of("type", "ack", "topicId", topicId, "seq", seq))))
                .onErrorResume(ex -> {
                    outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "ACK_ERROR", ex.getMessage()));
                    return Mono.empty();
                });
    }

    private Mono<Void> subscribe(String connectionId, UserContext user, ChatWebSocketOutbound outbound,
                                 JsonNode root, String commandId) {
        String topicId = root.path("topicId").asText(null);
        long afterSeq = root.path("afterSeq").asLong(0);
        if (topicId == null || topicId.isBlank()) {
            outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE", "topicId 不能为空"));
            return Mono.empty();
        }
        return Mono.fromCallable(() -> chatStreamService.ensureRunTopicAccessible(user, topicId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ignored -> {
                    if (connectionRegistry.get(connectionId).isEmpty()) {
                        return Mono.empty();
                    }
                    outbound.emit(ChatWebSocketEnvelopeDto.reply(commandId,
                            Map.of("type", "subscribe", "topicId", topicId, "recovered", afterSeq > 0,
                                    "lastSeq", afterSeq)));
                    Sinks.Empty<Void> cancellation = Sinks.empty();
                    connectionRegistry.subscribe(connectionId, topicId, afterSeq, cancellationDisposable(cancellation));
                    chatStreamService.resumeRunTopic(user, topicId, afterSeq)
                            .map(eventTranslator::toDto)
                            .takeUntilOther(cancellation.asMono())
                            .subscribe(
                                    dto -> emitTopicEvent(connectionId, outbound, topicId, cancellation, dto),
                                    ex -> {
                                        outbound.emit(ChatWebSocketEnvelopeDto.error(commandId,
                                                "SUBSCRIBE_ERROR", ex.getMessage()));
                                        connectionRegistry.unsubscribe(connectionId, topicId);
                                    }
                            );
                    return Mono.<Void>empty();
                })
                .onErrorResume(ex -> {
                    outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "SUBSCRIBE_ERROR", ex.getMessage()));
                    return Mono.<Void>empty();
                });
    }

    private void emitTopicEvent(String connectionId, ChatWebSocketOutbound outbound, String topicId,
                                Sinks.Empty<Void> cancellation, ChatEventDto dto) {
        if (dto == null) {
            return;
        }
        LocalWebSocketConnectionRegistry.DeliveryDecision decision =
                connectionRegistry.markDelivered(connectionId, topicId, dto.sequence());
        if (decision.action() == LocalWebSocketConnectionRegistry.Action.DELIVER) {
            outbound.emit(ChatWebSocketEnvelopeDto.message(topicId, dto));
        } else if (decision.action() == LocalWebSocketConnectionRegistry.Action.RECOVER_REQUIRED) {
            outbound.emit(ChatWebSocketEnvelopeDto.recoverRequired(topicId, decision.lastAckSeq(), decision.actualSeq()));
            // 发现乱序或缺口后暂停该 topic，避免前端恢复期间继续接收更高 seq。
            cancellation.tryEmitEmpty();
            connectionRegistry.unsubscribe(connectionId, topicId);
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
