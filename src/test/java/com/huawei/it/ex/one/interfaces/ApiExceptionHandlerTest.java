package com.huawei.it.ex.one.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.service.security.RegionalAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
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
    void mapsRegionalRestrictionToHttp451InBothWebStacks() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> servletResponse = handler.handleRegionalAccess(
                new RegionalAccessDeniedException(), servletRequest("/v1/chat/runs"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> reactiveResponse = reactiveHandler.handleRegionalAccess(
                new RegionalAccessDeniedException(), exchange("/v1/chat/runs"));

        assertRegionalRestriction(servletResponse);
        assertRegionalRestriction(reactiveResponse);
    }

    private void assertRegionalRestriction(ResponseEntity<ApiExceptionHandler.ApiErrorResponse> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAVAILABLE_FOR_LEGAL_REASONS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(451);
        assertThat(response.getBody().error()).isEqualTo("Unavailable For Legal Reasons");
        assertThat(response.getBody().code()).isEqualTo("SERVICE_REGION_RESTRICTED");
        assertThat(response.getBody().message())
                .isEqualTo("根据服务可用地区政策，您所在地区暂不支持使用本服务。");
    }

    private MockHttpServletRequest servletRequest(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
