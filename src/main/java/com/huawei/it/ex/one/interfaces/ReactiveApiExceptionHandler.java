package com.huawei.it.ex.one.interfaces;

import com.huawei.it.ex.one.application.integration.conversation.SessionSearchTimeoutException;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateQueryException;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceUnavailableException;
import com.huawei.it.ex.one.domain.chat.ActiveRunExistsException;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatShareUnavailableException;

import jakarta.validation.ConstraintViolationException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
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
@RestControllerAdvice(basePackages = "com.huawei.it.ex.one.interfaces")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ReactiveApiExceptionHandler {
    /** Preference recording is independent from run admission and reports a retryable 503. */
    @ExceptionHandler(IntentPreferenceUnavailableException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleIntentPreferenceUnavailable(
            IntentPreferenceUnavailableException ex, ServerWebExchange exchange) {
        return ApiExceptionHandler.error(HttpStatus.SERVICE_UNAVAILABLE, "INTENT_PREFERENCE_UNAVAILABLE",
                ex.getMessage(), requestPath(exchange));
    }

    /** 将候选技能下游失败映射为稳定的网关错误。 */
    @ExceptionHandler(IntentCandidateQueryException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleIntentCandidateQuery(
            IntentCandidateQueryException ex, ServerWebExchange exchange) {
        HttpStatus status = ex.isBusy()
                ? HttpStatus.TOO_MANY_REQUESTS
                : ex.timeout() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        String code = ex.isBusy()
                ? "INTENT_CANDIDATES_BUSY"
                : ex.timeout() ? "INTENT_CANDIDATES_TIMEOUT" : "INTENT_CANDIDATES_UPSTREAM_FAILED";
        return ApiExceptionHandler.error(status, code, ex.getMessage(), requestPath(exchange));
    }

    /** 将受保护的会话关键字查询超时映射为稳定的可重试响应。 */
    @ExceptionHandler(SessionSearchTimeoutException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleSessionSearchTimeout(
            SessionSearchTimeoutException ex, ServerWebExchange exchange) {
        return ApiExceptionHandler.error(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_SEARCH_TIMEOUT",
                ex.getMessage(), requestPath(exchange));
    }

    /**
     * 处理身份缺失和越权访问。
     *
     * @param ex 权限异常。
     * @param exchange Reactive 请求上下文，用于返回请求路径。
     * @return 身份缺失返回 401；资源越权返回 HTTP 200 和 ACCESS_DENIED 提示体。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleSecurity(SecurityException ex,
                                                                               ServerWebExchange exchange) {
        if (ApiExceptionHandler.isMissingIdentity(ex)) {
            return ApiExceptionHandler.error(HttpStatus.UNAUTHORIZED, "AUTH_CONTEXT_MISSING",
                    ex.getMessage(), requestPath(exchange));
        }
        return ApiExceptionHandler.accessDeniedPrompt(ex.getMessage(), requestPath(exchange));
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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex,
                                                                                  ServerWebExchange exchange) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return ApiExceptionHandler.error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, requestPath(exchange));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiExceptionHandler.ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                                         ServerWebExchange exchange) {
        return ApiExceptionHandler.error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), requestPath(exchange));
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
        if (ex instanceof ActiveRunExistsException) {
            return ApiExceptionHandler.error(HttpStatus.CONFLICT, "ACTIVE_RUN_EXISTS", ex.getMessage(), requestPath(exchange));
        }
        if (ex instanceof ChatInteractionUnavailableException interactionEx) {
            return ApiExceptionHandler.error(HttpStatus.CONFLICT, interactionEx.code(), interactionEx.getMessage(), requestPath(exchange));
        }
        if (ex instanceof ChatShareUnavailableException shareEx) {
            return ApiExceptionHandler.error(HttpStatus.CONFLICT, shareEx.code(), shareEx.getMessage(), requestPath(exchange));
        }
        return ApiExceptionHandler.error(HttpStatus.CONFLICT, "CONFLICT", ex.getMessage(), requestPath(exchange));
    }

    private String requestPath(ServerWebExchange exchange) {
        return exchange == null ? null : exchange.getRequest().getPath().value();
    }
}
