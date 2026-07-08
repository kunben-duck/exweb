package com.huawei.finance.front.one.interfaces.chat.websocket;

import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;

/**
 * WebSocket 路由配置。
 *
 * <p>使用 WebFlux 原生 HandlerMapping 暴露聊天 WebSocket，避免和 REST Controller 混在一起。</p>
 */
@Configuration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ChatWebSocketConfig {
    /**
     * 注册聊天 WebSocket 路由。
     *
     * @param handler 聊天 WebSocket 处理器。
     * @return WebFlux HandlerMapping。
     */
    @Bean
    public HandlerMapping chatWebSocketHandlerMapping(ChatWebSocketHandler handler) {
        SimpleUrlHandlerMapping mapping = new SimpleUrlHandlerMapping();
        Map<String, WebSocketHandler> urlMap = Map.of("/v1/chat/ws", handler);
        mapping.setUrlMap(urlMap);
        mapping.setOrder(-1);
        return mapping;
    }

    /**
     * 注册 WebSocketHandlerAdapter。
     *
     * @return WebFlux WebSocket 处理器适配器。
     */
    @Bean
    @ConditionalOnMissingBean(WebSocketHandlerAdapter.class)
    public WebSocketHandlerAdapter webSocketHandlerAdapter() {
        // WebFlux 需要该实现把 WebSocketHandler 接入响应链。
        return new WebSocketHandlerAdapter();
    }
}
