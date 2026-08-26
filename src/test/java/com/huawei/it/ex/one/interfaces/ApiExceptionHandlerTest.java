package com.huawei.it.ex.one.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.conversation.SessionSearchTimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * {@link ApiExceptionHandler} 的状态码映射测试。
 */
class ApiExceptionHandlerTest {
    private final ApiExceptionHandler handler = new ApiExceptionHandler();
    private final ReactiveApiExceptionHandler reactiveHandler = new ReactiveApiExceptionHandler();

    @Test
    void mapsResourceOwnershipViolationToSuccessPrompt() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = handler.handleSecurity(
                new SecurityException("文档不能绑定到不属于当前用户的会话"),
                servletRequest("/v1/documents")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(200);
        assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getBody().path()).isEqualTo("/v1/documents");
    }

    @Test
    void mapsMissingIdentityToUnauthorized() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = handler.handleSecurity(
                new SecurityException("当前租户 ID 缺失"),
                servletRequest("/v1/chat/runs")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("AUTH_CONTEXT_MISSING");
    }

    @Test
    void mapsBadRequestAndConflict() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> badRequest = handler.handleBadRequest(
                new IllegalArgumentException("sessionId 不能为空"),
                servletRequest("/v1/chat/sessions")
        );
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> conflict = handler.handleConflict(
                new IllegalStateException("文档当前不可用于聊天: PROCESSING"),
                servletRequest("/v1/documents/doc1/download")
        );

        assertThat(badRequest.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reactiveHandlerKeepsWebFluxPathMapping() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = reactiveHandler.handleBadRequest(
                new IllegalArgumentException("sessionId 不能为空"),
                exchange("/v1/chat/sessions")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path()).isEqualTo("/v1/chat/sessions");
    }

    @Test
    void reactiveHandlerMapsAccessDeniedToSuccessPrompt() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response = reactiveHandler.handleSecurity(
                new SecurityException("run 不存在或不属于当前用户"),
                exchange("/v1/chat/runs/run1/events/resume")
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo("ACCESS_DENIED");
        assertThat(response.getBody().path()).isEqualTo("/v1/chat/runs/run1/events/resume");
    }

    @Test
    void mapsSessionSearchTimeoutToServiceUnavailable() {
        SessionSearchTimeoutException exception = new SessionSearchTimeoutException(
                "会话关键字搜索超时，请稍后重试", new RuntimeException("timeout"));

        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> servletResponse =
                handler.handleSessionSearchTimeout(
                        exception, servletRequest("/v1/chat/sessions/page"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> reactiveResponse =
                reactiveHandler.handleSessionSearchTimeout(
                        exception, exchange("/v1/chat/sessions/page"));

        assertThat(servletResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(servletResponse.getBody()).isNotNull();
        assertThat(servletResponse.getBody().code()).isEqualTo("SESSION_SEARCH_TIMEOUT");
        assertThat(reactiveResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(reactiveResponse.getBody()).isNotNull();
        assertThat(reactiveResponse.getBody().code()).isEqualTo("SESSION_SEARCH_TIMEOUT");
    }

    private MockHttpServletRequest servletRequest(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
