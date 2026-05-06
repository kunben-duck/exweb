package com.huawei.finance.front.one.interfaces.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.finance.front.one.application.facade.FinanceChatFacade;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontChatEventDto;
import com.huawei.finance.front.one.interfaces.chat.dto.FrontChatRequest;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 聊天 WebSocket 入口。
 *
 * <p>客户端在同一个连接上发送 FrontChatRequest JSON，服务端把该请求产生的 ChatEvent
 * 逐条序列化为 FrontChatEventDto JSON 返回。</p>
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {
    private static final String PROTOCOL = "websocket";

    private final FinanceChatFacade chatFacade;
    private final ChatRequestTranslator requestTranslator;
    private final ChatEventTranslator eventTranslator;
    private final ObjectMapper objectMapper;

    public ChatWebSocketHandler(FinanceChatFacade chatFacade, ChatRequestTranslator requestTranslator,
                                ChatEventTranslator eventTranslator, ObjectMapper objectMapper) {
        this.chatFacade = chatFacade;
        this.requestTranslator = requestTranslator;
        this.eventTranslator = eventTranslator;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        Flux<WebSocketMessage> outbound = session.receive()
                .filter(message -> message.getType() == WebSocketMessage.Type.TEXT)
                // concatMap 保证同一连接内的多条用户消息按接收顺序处理和回写。
                .concatMap(message -> handleTextMessage(session, message.getPayloadAsText()))
                .onErrorResume(ex -> Flux.just(errorMessage(session, "WS_STREAM_ERROR", ex.getMessage())));
        return session.send(outbound);
    }

    private Flux<WebSocketMessage> handleTextMessage(WebSocketSession session, String payload) {
        FrontChatRequest request;
        try {
            // WebSocket 只接收文本 JSON；解析失败时返回协议内错误事件，不关闭连接。
            request = objectMapper.readValue(payload, FrontChatRequest.class);
        } catch (Exception ex) {
            return Flux.just(errorMessage(session, "BAD_WS_MESSAGE", ex.getMessage()));
        }
        return chatFacade.chat(requestTranslator.toCommand(request, PROTOCOL))
                .map(eventTranslator::toDto)
                .map(dto -> toMessage(session, dto))
                .onErrorResume(ex -> Flux.just(errorMessage(session, "RUN_ERROR", ex.getMessage())));
    }

    private WebSocketMessage toMessage(WebSocketSession session, FrontChatEventDto dto) {
        try {
            return session.textMessage(objectMapper.writeValueAsString(dto));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("WebSocket 响应序列化失败", ex);
        }
    }

    private WebSocketMessage errorMessage(WebSocketSession session, String code, String message) {
        FrontChatEventDto dto = new FrontChatEventDto(null, null, 0, "run.failed", "system",
                Map.of("code", code, "message", message == null ? "" : message));
        return toMessage(session, dto);
    }
}
