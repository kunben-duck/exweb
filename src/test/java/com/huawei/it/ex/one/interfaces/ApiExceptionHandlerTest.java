/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.interfaces;

import static org.assertj.core.api.Assertions.assertThat;

import com.huawei.it.ex.one.application.integration.conversation.SessionSearchTimeoutException;
import com.huawei.it.ex.one.application.integration.intent.IntentCandidateQueryException;
import com.huawei.it.ex.one.application.integration.intent.IntentPreferenceUnavailableException;
import com.huawei.it.ex.one.domain.chat.CandidateSwitchConflictException;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackNotReadyException;
import com.huawei.it.ex.one.domain.chat.DomainAgentAsyncCallbackPayloadTooLargeException;

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
    void mapsCandidateSwitchConflictsToStableCodes() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> pending = handler.handleConflict(
                CandidateSwitchConflictException.stopPending("run_a"),
                servletRequest("/v1/chat/runs/run_a/switch-domain-agent"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> stale = reactiveHandler.handleConflict(
                CandidateSwitchConflictException.staleSource("run_a"),
                exchange("/v1/chat/runs/run_a/switch-domain-agent"));

        assertThat(pending.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(pending.getBody()).isNotNull();
        assertThat(pending.getBody().code()).isEqualTo("CANDIDATE_SWITCH_STOP_PENDING");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).isNotNull();
        assertThat(stale.getBody().code()).isEqualTo("CANDIDATE_SWITCH_STALE_SOURCE");
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

    @Test
    void mapsIntentCandidateFailuresToGatewayStatuses() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> timeout =
                handler.handleIntentCandidateQuery(
                        IntentCandidateQueryException.timeout(new RuntimeException("timeout")),
                        servletRequest("/v1/chat/intent-candidates"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> upstream =
                reactiveHandler.handleIntentCandidateQuery(
                        IntentCandidateQueryException.upstream("upstream failed"),
                        exchange("/v1/chat/intent-candidates"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> busy =
                handler.handleIntentCandidateQuery(
                        IntentCandidateQueryException.busy(),
                        servletRequest("/v1/chat/intent-candidates"));

        assertThat(timeout.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(timeout.getBody()).isNotNull();
        assertThat(timeout.getBody().code()).isEqualTo("INTENT_CANDIDATES_TIMEOUT");
        assertThat(upstream.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(upstream.getBody()).isNotNull();
        assertThat(upstream.getBody().code()).isEqualTo("INTENT_CANDIDATES_UPSTREAM_FAILED");
        assertThat(busy.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(busy.getBody()).isNotNull();
        assertThat(busy.getBody().code()).isEqualTo("INTENT_CANDIDATES_BUSY");
    }

    @Test
    void mapsIntentPreferenceWriteFailureToServiceUnavailable() {
        IntentPreferenceUnavailableException exception = new IntentPreferenceUnavailableException(
                "意图偏好记录暂不可用，请稍后重试", new RuntimeException("database down"));

        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> servletResponse =
                handler.handleIntentPreferenceUnavailable(
                        exception, servletRequest("/v1/chat/intent-preference-corrections"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> reactiveResponse =
                reactiveHandler.handleIntentPreferenceUnavailable(
                        exception, exchange("/v1/chat/intent-preference-corrections"));

        assertThat(servletResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(servletResponse.getBody()).isNotNull();
        assertThat(servletResponse.getBody().code()).isEqualTo("INTENT_PREFERENCE_UNAVAILABLE");
        assertThat(reactiveResponse.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(reactiveResponse.getBody()).isNotNull();
        assertThat(reactiveResponse.getBody().code()).isEqualTo("INTENT_PREFERENCE_UNAVAILABLE");
    }

    @Test
    void mapsAsyncCallbackNotReadyToRetryableConflict() {
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> servletResponse =
                handler.handleDomainAgentAsyncCallbackNotReady(
                        new DomainAgentAsyncCallbackNotReadyException(),
                        servletRequest("/v1/internal/domain-agent/async-tasks/callback"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> reactiveResponse =
                reactiveHandler.handleDomainAgentAsyncCallbackNotReady(
                        new DomainAgentAsyncCallbackNotReadyException(),
                        exchange("/v1/internal/domain-agent/async-tasks/callback"));

        assertThat(servletResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(servletResponse.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(servletResponse.getBody()).isNotNull();
        assertThat(servletResponse.getBody().code()).isEqualTo("DOMAIN_AGENT_ASYNC_NOT_READY");
        assertThat(reactiveResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reactiveResponse.getHeaders().getFirst("Retry-After")).isEqualTo("1");
    }

    @Test
    void mapsAsyncCallbackCapacityOverflowToPayloadTooLarge() {
        DomainAgentAsyncCallbackPayloadTooLargeException exception =
                new DomainAgentAsyncCallbackPayloadTooLargeException("too large");

        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> servletResponse =
                handler.handleDomainAgentAsyncCallbackPayloadTooLarge(
                        exception, servletRequest("/v1/internal/domain-agent/async-tasks/callback"));
        ResponseEntity<ApiExceptionHandler.ApiErrorResponse> reactiveResponse =
                reactiveHandler.handleDomainAgentAsyncCallbackPayloadTooLarge(
                        exception, exchange("/v1/internal/domain-agent/async-tasks/callback"));

        assertThat(servletResponse.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(servletResponse.getBody()).isNotNull();
        assertThat(servletResponse.getBody().code()).isEqualTo("DOMAIN_AGENT_ASYNC_CALLBACK_TOO_LARGE");
        assertThat(reactiveResponse.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    private MockHttpServletRequest servletRequest(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private MockServerWebExchange exchange(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.get(path).build());
    }
}
