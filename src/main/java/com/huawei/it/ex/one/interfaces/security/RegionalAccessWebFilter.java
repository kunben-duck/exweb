package com.huawei.it.ex.one.interfaces.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.it.ex.one.application.config.RegionalAccessProperties;
import com.huawei.it.ex.one.application.integration.identity.AuthContextProvider;
import com.huawei.it.ex.one.application.integration.security.RegionalAccessDecision;
import com.huawei.it.ex.one.application.service.security.RegionalAccessAuthorizer;
import com.huawei.it.ex.one.application.service.security.RegionalAccessDeniedException;
import com.huawei.it.ex.one.domain.auth.UserContext;
import com.huawei.it.ex.one.interfaces.ApiExceptionHandler;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive counterpart of the Servlet regional access interceptor.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class RegionalAccessWebFilter implements WebFilter, Ordered {
    private static final String CHAT_WEBSOCKET_PATH = "/v1/chat/ws";

    private final AuthContextProvider auth;
    private final RegionalAccessAuthorizer authorizer;
    private final TrustedClientIpResolver clientIpResolver;
    private final RegionalAccessProperties properties;
    private final ObjectMapper objectMapper;

    public RegionalAccessWebFilter(AuthContextProvider auth, RegionalAccessAuthorizer authorizer,
                                   TrustedClientIpResolver clientIpResolver,
                                   RegionalAccessProperties properties, ObjectMapper objectMapper) {
        this.auth = auth;
        this.authorizer = authorizer;
        this.clientIpResolver = clientIpResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public int getOrder() {
        return properties.getInterceptorOrder();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!properties.isEnabled() || !path.startsWith("/v1/")
                || CHAT_WEBSOCKET_PATH.equals(path)
                || HttpMethod.OPTIONS.equals(exchange.getRequest().getMethod())) {
            return chain.filter(exchange);
        }

        UserContext user;
        try {
            user = auth.resolve();
        } catch (SecurityException ex) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "AUTH_CONTEXT_MISSING", ex.getMessage());
        }
        if (user == null) {
            return writeError(exchange, HttpStatus.UNAUTHORIZED, "AUTH_CONTEXT_MISSING", "当前用户身份缺失");
        }
        String clientIp = clientIpResolver.resolve(
                exchange.getRequest().getHeaders().getFirst(properties.normalizedIpHeaderName()));
        return authorizer.authorize(user, clientIp)
                .onErrorReturn(RegionalAccessDecision.ALLOW)
                .flatMap(decision -> decision == RegionalAccessDecision.BLOCK
                        ? writeError(exchange, HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS,
                                RegionalAccessDeniedException.CODE, RegionalAccessDeniedException.DEFAULT_MESSAGE)
                        : chain.filter(exchange));
    }

    private Mono<Void> writeError(ServerWebExchange exchange, HttpStatus status, String code, String message) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        ApiExceptionHandler.ApiErrorResponse body = new ApiExceptionHandler.ApiErrorResponse(
                Instant.now(), exchange.getRequest().getPath().value(), status.value(),
                status.getReasonPhrase(), code, message);
        try {
            byte[] json = objectMapper.writeValueAsBytes(body);
            DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(json);
            return exchange.getResponse().writeWith(Mono.just(buffer));
        } catch (JsonProcessingException ex) {
            return exchange.getResponse().setComplete();
        }
    }
}
