package com.huawei.it.ex.one.interfaces;

import com.huawei.it.ex.one.application.integration.conversation.SessionSearchTimeoutException;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateQueryException;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceUnavailableException;
import com.huawei.it.ex.one.domain.chat.ActiveRunExistsException;
import com.huawei.it.ex.one.domain.chat.CandidateSwitchConflictException;
import com.huawei.it.ex.one.domain.chat.ChatInteractionUnavailableException;
import com.huawei.it.ex.one.domain.chat.ChatShareUnavailableException;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackBusyException;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackNotReadyException;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackPayloadTooLargeException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * Servlet/MVC 模式下的 HTTP API 统一异常映射。
 *
 * <p>应用层会使用 {@link IllegalArgumentException} 表达参数错误，使用 {@link SecurityException}
 * 表达身份缺失或资源越权。身份缺失仍返回 401；资源不存在或不属于当前用户时返回 HTTP 200
 * 并在响应体里携带 {@code ACCESS_DENIED}，便于前端以普通业务提示处理。</p>
 */
@RestControllerAdvice(basePackages = "com.huawei.it.ex.one.interfaces")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ApiExceptionHandler {

    @ExceptionHandler(DomainAgentAsyncCallbackBusyException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainAgentAsyncCallbackBusy(
            DomainAgentAsyncCallbackBusyException ex, HttpServletRequest request) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "DOMAIN_AGENT_ASYNC_CALLBACK_BUSY",
                ex.getMessage(), requestPath(request));
    }

    @ExceptionHandler(DomainAgentAsyncCallbackNotReadyException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainAgentAsyncCallbackNotReady(
            DomainAgentAsyncCallbackNotReadyException ex, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header("Retry-After", "1")
                .body(errorBody(HttpStatus.CONFLICT, "DOMAIN_AGENT_ASYNC_NOT_READY",
                        ex.getMessage(), requestPath(request)));
    }

    @ExceptionHandler(DomainAgentAsyncCallbackPayloadTooLargeException.class)
    public ResponseEntity<ApiErrorResponse> handleDomainAgentAsyncCallbackPayloadTooLarge(
            DomainAgentAsyncCallbackPayloadTooLargeException ex, HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "DOMAIN_AGENT_ASYNC_CALLBACK_TOO_LARGE",
                ex.getMessage(), requestPath(request));
    }

    /** Preference recording is independent from run admission and reports a retryable 503. */
    @ExceptionHandler(IntentPreferenceUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> handleIntentPreferenceUnavailable(
            IntentPreferenceUnavailableException ex, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "INTENT_PREFERENCE_UNAVAILABLE",
                ex.getMessage(), requestPath(request));
    }

    /** 将候选技能下游失败映射为稳定的网关错误。 */
    @ExceptionHandler(IntentCandidateQueryException.class)
    public ResponseEntity<ApiErrorResponse> handleIntentCandidateQuery(
            IntentCandidateQueryException ex, HttpServletRequest request) {
        HttpStatus status = ex.isBusy()
                ? HttpStatus.TOO_MANY_REQUESTS
                : ex.timeout() ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY;
        String code = ex.isBusy()
                ? "INTENT_CANDIDATES_BUSY"
                : ex.timeout() ? "INTENT_CANDIDATES_TIMEOUT" : "INTENT_CANDIDATES_UPSTREAM_FAILED";
        return error(status, code, ex.getMessage(), requestPath(request));
    }

    /** 将受保护的会话关键字查询超时映射为稳定的可重试响应。 */
    @ExceptionHandler(SessionSearchTimeoutException.class)
    public ResponseEntity<ApiErrorResponse> handleSessionSearchTimeout(
            SessionSearchTimeoutException ex, HttpServletRequest request) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "SESSION_SEARCH_TIMEOUT",
                ex.getMessage(), requestPath(request));
    }

    /**
     * 处理身份缺失和越权访问。
     *
     * @param ex 权限异常。
     * @param request 当前 Servlet 请求上下文，用于返回请求路径。
     * @return 身份缺失返回 401；资源越权返回 HTTP 200 和 ACCESS_DENIED 提示体。
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<ApiErrorResponse> handleSecurity(SecurityException ex, HttpServletRequest request) {
        if (isMissingIdentity(ex)) {
            return error(HttpStatus.UNAUTHORIZED, "AUTH_CONTEXT_MISSING", ex.getMessage(), requestPath(request));
        }
        return accessDeniedPrompt(ex.getMessage(), requestPath(request));
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
     * 处理 Bean Validation 请求体校验失败。
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleInvalidBody(MethodArgumentNotValidException ex,
                                                              HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse("请求参数校验失败");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message, requestPath(request));
    }

    /**
     * 处理 query/path 等参数校验失败。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                      HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", ex.getMessage(), requestPath(request));
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
        if (ex instanceof ActiveRunExistsException) {
            return error(HttpStatus.CONFLICT, "ACTIVE_RUN_EXISTS", ex.getMessage(), requestPath(request));
        }
        if (ex instanceof CandidateSwitchConflictException switchEx) {
            return error(HttpStatus.CONFLICT, switchEx.code(), switchEx.getMessage(), requestPath(request));
        }
        if (ex instanceof ChatInteractionUnavailableException interactionEx) {
            return error(HttpStatus.CONFLICT, interactionEx.code(), interactionEx.getMessage(), requestPath(request));
        }
        if (ex instanceof ChatShareUnavailableException shareEx) {
            return error(HttpStatus.CONFLICT, shareEx.code(), shareEx.getMessage(), requestPath(request));
        }
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
        return ResponseEntity.status(status).body(errorBody(status, code, message, path));
    }

    private static ApiErrorResponse errorBody(HttpStatus status, String code, String message, String path) {
        return new ApiErrorResponse(
                Instant.now(),
                path,
                status.value(),
                status.getReasonPhrase(),
                code,
                message == null || message.isBlank() ? status.getReasonPhrase() : message
        );
    }

    /**
     * 构造资源不可访问的业务提示响应。
     *
     * <p>这类场景通常由前端缓存了已删除、已归档或不属于当前用户的数据导致。使用 HTTP 200
     * 可以避免企业统一错误页拦截，同时保留稳定 code/message 给前端展示和清理本地状态。</p>
     */
    static ResponseEntity<ApiErrorResponse> accessDeniedPrompt(String message, String path) {
        HttpStatus status = HttpStatus.OK;
        return ResponseEntity.ok(new ApiErrorResponse(
                Instant.now(),
                path,
                status.value(),
                status.getReasonPhrase(),
                "ACCESS_DENIED",
                message == null || message.isBlank() ? "当前数据不可访问或已不存在" : message
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
