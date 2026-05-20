package com.huawei.finance.front.one.interfaces.chat;

import com.huawei.finance.front.one.application.config.ChatWebSocketProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Servlet/MVC 启动模式下的前端 WebSocket 路由配置。
 *
 * <p>当企业框架引入 {@code spring-boot-starter-web} 时，Spring Boot 默认选择 Servlet
 * 应用类型。此时 WebFlux {@code SimpleUrlHandlerMapping} 不会处理升级请求，所以这里用
 * Servlet WebSocket 注册与 WebFlux 模式完全相同的路径和协议。</p>
 */
@Configuration
@EnableWebSocket
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatServletWebSocketConfig implements WebSocketConfigurer {
    private final ChatServletWebSocketHandler handler;
    private final ChatServletWebSocketAuthInterceptor authInterceptor;
    private final ChatWebSocketProperties properties;

    public ChatServletWebSocketConfig(ChatServletWebSocketHandler handler,
                                      ChatServletWebSocketAuthInterceptor authInterceptor,
                                      ChatWebSocketProperties properties) {
        this.handler = handler;
        this.authInterceptor = authInterceptor;
        this.properties = properties;
    }

    /**
     * 注册前端 WebSocket 路径。
     *
     * <p>如果应用配置了 {@code server.servlet.context-path=/fin/ex}，最终访问路径会自然变为
     * {@code /fin/ex/api/v1/ex/chat/ws}。</p>
     *
     * @param registry Servlet WebSocket handler 注册表。
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/ex/chat/ws")
                .addInterceptors(authInterceptor)
                .setAllowedOriginPatterns(properties.allowedOriginPatternArray());
    }
}
