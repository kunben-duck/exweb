package com.huawei.finance.front.one.interfaces.chat.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.config.ChatStreamProperties;
import com.huawei.finance.front.one.application.service.chat.ChatStreamApplicationService;
import com.huawei.finance.front.one.application.service.chat.StreamRecoveryRequiredException;
import com.huawei.finance.front.one.application.service.security.PermissionChecker;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatRun;
import com.huawei.finance.front.one.interfaces.chat.ChatEventTranslator;
import com.huawei.finance.front.one.interfaces.chat.ChatTurnStreamTranslator;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatEventDto;
import com.huawei.finance.front.one.interfaces.chat.dto.ChatWebSocketEnvelopeDto;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
 * 订阅、乱序恢复提示和订阅释放。它不依赖 WebFlux 或 Servlet 具体连接类型，
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
    private final ChatTurnStreamTranslator turnStreamTranslator;
    private final ChatStreamProperties chatStreamProperties;
    private final ObjectMapper objectMapper;

    public ChatWebSocketProtocolService(PermissionChecker permissionChecker,
                                        ChatStreamApplicationService chatStreamService,
                                        LocalWebSocketConnectionRegistry connectionRegistry,
                                        ChatEventTranslator eventTranslator,
                                        ChatTurnStreamTranslator turnStreamTranslator,
                                        ChatStreamProperties chatStreamProperties,
                                        ObjectMapper objectMapper) {
        this.permissionChecker = permissionChecker;
        this.chatStreamService = chatStreamService;
        this.connectionRegistry = connectionRegistry;
        this.eventTranslator = eventTranslator;
        this.turnStreamTranslator = turnStreamTranslator;
        this.chatStreamProperties = chatStreamProperties;
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
        connectionRegistry.unregister(connectionId);
    }

    /**
     * 判断连接是否空闲超时。
     *
     * @param connectionId 当前物理 WebSocket 连接 ID。
     * @param idleTimeout 空闲阈值。
     * @return true 表示连接应由入口 handler 主动关闭。
     */
    public boolean idleForLongerThan(String connectionId, Duration idleTimeout) {
        return connectionRegistry.idleForLongerThan(connectionId, idleTimeout);
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
        outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "BAD_WS_MESSAGE",
                "不支持的 WebSocket command type: " + type));
        return Mono.empty();
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
                .flatMap(run -> {
                    if (connectionRegistry.get(connectionId).isEmpty()) {
                        return Mono.empty();
                    }
                    Sinks.Empty<Void> cancellation = Sinks.empty();
                    try {
                        connectionRegistry.subscribe(connectionId, topicId, run.sessionId(), afterSeq,
                                cancellationDisposable(cancellation));
                    } catch (RuntimeException ex) {
                        cancellation.tryEmitEmpty();
                        return Mono.error(ex);
                    }
                    outbound.emit(ChatWebSocketEnvelopeDto.reply(commandId,
                            Map.of("type", "subscribe", "topicId", topicId, "recovered", afterSeq > 0,
                                    "lastSeq", afterSeq)));
                    AtomicLong lastSeq = new AtomicLong(afterSeq);
                    Disposable heartbeat = startTurnHeartbeat(outbound, topicId, run, lastSeq, cancellation);
                    chatStreamService.resumeRunTopic(user, topicId, afterSeq)
                            .map(eventTranslator::toDto)
                            .takeUntilOther(cancellation.asMono())
                            .doFinally(ignored -> heartbeat.dispose())
                            .subscribe(
                                    dto -> emitTopicEvent(connectionId, outbound, topicId, run, cancellation, lastSeq, dto),
                                    ex -> handleSubscriptionError(connectionId, outbound, topicId, commandId, afterSeq, ex)
                            );
                    return Mono.<Void>empty();
                })
                .onErrorResume(ex -> {
                    outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "SUBSCRIBE_ERROR", ex.getMessage()));
                    return Mono.<Void>empty();
                });
    }

    private void handleSubscriptionError(String connectionId, ChatWebSocketOutbound outbound, String topicId,
                                         String commandId, long afterSeq, Throwable ex) {
        if (ex instanceof StreamRecoveryRequiredException recovery) {
            outbound.emit(ChatWebSocketEnvelopeDto.recoverRequired(topicId, afterSeq, recovery.afterSeq()));
        } else {
            outbound.emit(ChatWebSocketEnvelopeDto.error(commandId, "SUBSCRIBE_ERROR", ex.getMessage()));
        }
        connectionRegistry.unsubscribe(connectionId, topicId);
    }

    private Disposable startTurnHeartbeat(ChatWebSocketOutbound outbound, String topicId, ChatRun run,
                                          AtomicLong lastSeq, Sinks.Empty<Void> cancellation) {
        Duration interval = chatStreamProperties.normalizedTurnHeartbeatInterval();
        if (interval.isZero() || interval.isNegative()) {
            return () -> { };
        }
        return reactor.core.publisher.Flux.interval(interval)
                .takeUntilOther(cancellation.asMono())
                .subscribe(
                        ignored -> outbound.emit(ChatWebSocketEnvelopeDto.message(
                                topicId,
                                turnStreamTranslator.heartbeat(run.sessionId(), run.id(), lastSeq.get()),
                                null
                        )),
                        ex -> log.warn("WebSocket turn heartbeat 发送失败，topicId={}, reason={}", topicId, ex.getMessage())
                );
    }

    private void emitTopicEvent(String connectionId, ChatWebSocketOutbound outbound, String topicId, ChatRun run,
                                Sinks.Empty<Void> cancellation, AtomicLong lastSeq, ChatEventDto dto) {
        if (dto == null) {
            return;
        }
        if (!run.id().equals(dto.runId()) || !run.sessionId().equals(dto.sessionId())) {
            /*
             * topicId、runId、sessionId 三者必须同时匹配。出现不匹配说明 live source 或 Redis fanout
             * 发生了错误投递；这里直接丢弃，避免跨会话实时消息串到当前连接。
             */
            log.warn("Dropped mismatched WebSocket event. topicId={}, expectedRunId={}, actualRunId={}, expectedSessionId={}, actualSessionId={}, seq={}",
                    topicId, run.id(), dto.runId(), run.sessionId(), dto.sessionId(), dto.sequence());
            return;
        }
        LocalWebSocketConnectionRegistry.DeliveryDecision decision =
                connectionRegistry.markDelivered(connectionId, topicId, dto.sequence());
        if (decision.action() == LocalWebSocketConnectionRegistry.Action.DELIVER) {
            lastSeq.set(dto.sequence());
            outbound.emit(ChatWebSocketEnvelopeDto.message(
                    topicId,
                    turnStreamTranslator.streamItem(dto),
                    String.valueOf(dto.sequence())
            ));
            if (turnStreamTranslator.isTerminal(dto)) {
                outbound.emit(ChatWebSocketEnvelopeDto.message(
                        topicId,
                        turnStreamTranslator.done(dto.sessionId(), dto.runId(), dto.sequence(), dto.type()),
                        null
                ));
            }
        } else if (decision.action() == LocalWebSocketConnectionRegistry.Action.RECOVER_REQUIRED) {
            outbound.emit(ChatWebSocketEnvelopeDto.recoverRequired(topicId, decision.resumeAfterSeq(), decision.actualSeq()));
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
