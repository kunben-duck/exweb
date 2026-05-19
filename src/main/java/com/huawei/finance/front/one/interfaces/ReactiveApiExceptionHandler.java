package com.huawei.finance.front.one.interfaces;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;

/**
 * Reactive WebFlux 模式下的 HTTP API 统一异常映射。
 *
 * <p>当前项目默认可在 MVC/Servlet 与 WebFlux 两种服务端栈下启动。MVC 不能解析
 * {@link ServerWebExchange}，WebFlux 也不能解析 Servlet 请求对象，因此异常处理器必须按
 * web application type 分开注册，避免业务异常在异常处理阶段二次失败。</p>
 */
@RestControllerAdvice(basePackages = "com.huawei.finance.front.one.interfaces")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveApiExceptionHandler {
    /**
     * 处理身份缺失和越权访问。
     *
     * @param ex 权限异常。
     * @param exchange Reactive 请求上下文，用于返回请求路径。
     * @return 身份缺失返回 401，资源越权返回 403。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleSecurity(SecurityException ex,
                                                                               ServerWebExchange exchange) {
        HttpStatus status = ApiExceptionHandler.isMissingIdentity(ex) ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        String code = status == HttpStatus.UNAUTHORIZED ? "AUTH_CONTEXT_MISSING" : "ACCESS_DENIED";
        return ApiExceptionHandler.error(status, code, ex.getMessage(), requestPath(exchange));
    }

    /**
     * 处理请求参数或路径参数错误。
     *
     * @param ex 参数异常。
     * @param exchange Reactive 请求上下文，用于返回请求路径。
     * @return 400 bad request。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleBadRequest(IllegalArgumentException ex,
                                                                                 ServerWebExchange exchange) {
        return ApiExceptionHandler.error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), requestPath(exchange));
    }

    /**
     * 处理资源当前状态不允许执行操作的场景。
     *
     * @param ex 状态冲突异常。
     * @param exchange Reactive 请求上下文，用于返回请求路径。
     * @return 409 conflict。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleConflict(IllegalStateException ex,
                                                                               ServerWebExchange exchange) {
        return ApiExceptionHandler.error(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), requestPath(exchange));
    }

    private String requestPath(ServerWebExchange exchange) {
        return exchange == null ? null : exchange.getRequest().getPath().value();
    }
}
