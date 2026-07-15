package com.huawei.it.ex.one.interfaces.chat.websocket;

import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.service.security.PermissionChecker;
import com.huawei.it.ex.one.domain.auth.UserContext;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

/**
 * Servlet/MVC WebSocket 握手鉴权拦截器。
 *
 * <p>企业权限框架通常在普通 HTTP 请求线程中通过 ThreadLocal 暴露用户身份。WebSocket 完成
 * upgrade 后，后续回调线程不一定还能访问该 ThreadLocal，因此这里必须在 {@link #beforeHandshake}
 * 中解析并固化 {@link UserContext}。</p>
 */
@Component
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ChatServletWebSocketAuthInterceptor implements HandshakeInterceptor {
    private static final Logger log = LoggerFactory.getLogger(ChatServletWebSocketAuthInterceptor.class);

    private final AuthContextProvider auth;
    private final PermissionChecker permissionChecker;

    public ChatServletWebSocketAuthInterceptor(AuthContextProvider auth, PermissionChecker permissionChecker) {
        this.auth = auth;
        this.permissionChecker = permissionChecker;
    }

    /**
     * 在 HTTP upgrade 之前解析用户身份，并写入 WebSocket attributes。
     *
     * @param request WebSocket 握手请求。
     * @param response WebSocket 握手响应。
     * @param wsHandler 目标 WebSocket handler。
     * @param attributes 将被复制到 WebSocketSession 的 attributes。
     * @return true 表示允许升级；false 表示拒绝连接。
     */
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            UserContext user = auth.resolve();
            permissionChecker.checkChatPermission(user);
            ChatWebSocketUserContextAttributes.put(attributes, user);
            return true;
        } catch (SecurityException ex) {
            setStatus(response, HttpStatus.UNAUTHORIZED);
            log.warn("WebSocket handshake authentication failed: {}", ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            setStatus(response, HttpStatus.FORBIDDEN);
            log.warn("WebSocket handshake permission check failed: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 握手结束无需清理。UserContext 已作为不可变快照进入 WebSocketSession attributes。
    }

    private void setStatus(ServerHttpResponse response, HttpStatus status) {
        if (response != null) {
            response.setStatusCode(status);
        }
    }
}
