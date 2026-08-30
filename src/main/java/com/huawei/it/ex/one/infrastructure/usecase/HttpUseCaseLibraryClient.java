/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.huawei.it.ex.one.infrastructure.usecase;

import com.huawei.it.ex.one.application.integration.auth.AuthHeaderRequest;
import com.huawei.it.ex.one.application.integration.usecase.UseCaseLibraryClient;
import com.huawei.it.ex.one.application.integration.usecase.UseCaseMatchRequest;
import com.huawei.it.ex.one.application.service.auth.AuthHeaderProviderRegistry;
import com.huawei.it.ex.one.domain.usecase.UseCaseMatchResult;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * 用例库服务 HTTP 适配器。
 *
 * <p>用例库是简单任务的第一优先级路由信号。这里只负责协议转换，命中阈值和后续路由仍由
 * RoutingPolicy/FinanceEXChatService 控制。更换用例库部署地址时，只需要调整
 * financeex.use-case-library.base-url 和 match-path。</p>
 */
@Component
public class HttpUseCaseLibraryClient implements UseCaseLibraryClient {
    private final WebClient webClient;
    private final String baseUrl;
    private final String matchPath;
    private final Duration timeout;
    private final AuthHeaderProviderRegistry authHeaders;

    public HttpUseCaseLibraryClient(WebClient.Builder webClientBuilder,
                                    @Value("${financeex.use-case-library.base-url:}") String baseUrl,
                                    @Value("${financeex.use-case-library.match-path:/v1/use-cases/match}") String matchPath,
                                    @Value("${financeex.use-case-library.timeout:5s}") Duration timeout,
                                    AuthHeaderProviderRegistry authHeaders) {
        this.webClient = baseUrl == null || baseUrl.isBlank()
                ? webClientBuilder.build()
                : webClientBuilder.baseUrl(baseUrl.trim()).build();
        this.baseUrl = baseUrl;
        this.matchPath = matchPath;
        this.timeout = timeout;
        this.authHeaders = authHeaders;
    }

    @Override
    public UseCaseMatchResult match(UseCaseMatchRequest request) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("financeex.use-case-library.base-url 不能为空");
        }
        return webClient.post()
                .uri(matchPath)
                .headers(headers -> applyAuthHeaders(headers, request))
                .bodyValue(request)
                .retrieve()
                .bodyToMono(UseCaseMatchResult.class)
                .timeout(timeout)
                .blockOptional()
                .orElseGet(() -> UseCaseMatchResult.notMatched("empty use case response"));
    }

    private void applyAuthHeaders(HttpHeaders headers, UseCaseMatchRequest request) {
        authHeaders.headers(new AuthHeaderRequest(
                request == null ? null : request.tenantId(),
                request == null ? null : request.userId(),
                "use-case-library",
                "match",
                baseUrl,
                matchPath,
                null
        )).forEach(headers::set);
    }
}
