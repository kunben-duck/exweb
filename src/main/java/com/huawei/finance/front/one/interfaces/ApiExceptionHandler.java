package com.huawei.finance.front.one.interfaces;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Servlet/MVC 模式下的 HTTP API 统一异常映射。
 *
 * <p>应用层会使用 {@link IllegalArgumentException} 表达参数错误，使用 {@link SecurityException}
 * 表达身份缺失或资源越权。接口层必须把这些可预期业务异常转换成稳定 HTTP 状态码，
 * 避免 MVC 默认错误处理把它们暴露为 500。</p>
 */
@RestControllerAdvice(basePackages = "com.huawei.finance.front.one.interfaces")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler {

    /**
     * 处理身份缺失和越权访问。
     *
     * @param ex 权限异常。
     * @param request 当前 Servlet 请求上下文，用于返回请求路径。
     * @return 身份缺失返回 401，资源越权返回 403。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiErrorResponse> handleSecurity(SecurityException ex, HttpServletRequest request) {
        HttpStatus status = isMissingIdentity(ex) ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        String code = status == HttpStatus.UNAUTHORIZED ? "AUTH_CONTEXT_MISSING" : "ACCESS_DENIED";
        return error(status, code, ex.getMessage(), requestPath(request));
    }

    /**
     * 处理请求参数或路径参数错误。
     *
     * @param ex 参数异常。
     * @param request 当前 Servlet 请求上下文，用于返回请求路径。
     * @return 400 bad request。
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage(), requestPath(request));
    }

    /**
     * 处理资源当前状态不允许执行操作的场景。
     *
     * @param ex 状态冲突异常。
     * @param request 当前 Servlet 请求上下文，用于返回请求路径。
     * @return 409 conflict。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiErrorResponse> handleConflict(IllegalStateException ex, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), requestPath(request));
    }

    /**
     * 构造稳定错误响应。
     *
     * @param status HTTP 状态。
     * @param code 稳定错误码。
     * @param message 错误说明。
     * @param path 请求路径。
     * @return HTTP 错误响应。
     */
    static ResponseEntity<ApiErrorResponse> error(HttpStatus status, String code, String message, String path) {
        return ResponseEntity.status(status).body(new ApiErrorResponse(
                Instant.now(),
                path,
                status.value(),
                status.getReasonPhrase(),
                code,
                message == null || message.isBlank() ? status.getReasonPhrase() : message
        ));
    }

    /**
     * 根据异常文案判断是否为身份上下文缺失。
     *
     * @param ex 权限异常。
     * @return 身份缺失返回 true，普通越权返回 false。
     */
    static boolean isMissingIdentity(SecurityException ex) {
        String message = ex.getMessage();
        return message != null && message.contains("缺失");
    }

    private String requestPath(HttpServletRequest request) {
        return request == null ? null : request.getRequestURI();
    }

    /**
     * 统一 HTTP 错误响应体。
     *
     * @param timestamp 生成错误响应的服务端时间。
     * @param path 触发错误的 HTTP 路径。
     * @param status HTTP 状态码。
     * @param error HTTP 状态短语。
     * @param code 稳定错误码，供前端和网关识别。
     * @param message 可展示或可诊断的错误说明。
     */
    public record ApiErrorResponse(
            Instant timestamp,
            String path,
            int status,
            String error,
            String code,
            String message
    ) {}
}
