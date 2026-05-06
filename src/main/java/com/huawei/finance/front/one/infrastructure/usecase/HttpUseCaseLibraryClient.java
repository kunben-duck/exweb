package com.huawei.finance.front.one.infrastructure.usecase;

import com.huawei.finance.front.one.application.integration.usecase.UseCaseLibraryClient;
import com.huawei.finance.front.one.application.integration.usecase.UseCaseMatchRequest;
import com.huawei.finance.front.one.domain.usecase.UseCaseMatchResult;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 用例库服务 HTTP 适配器。
 *
 * <p>用例库是简单任务的第一优先级路由信号。这里只负责协议转换，命中阈值和后续路由仍由
 * RoutingPolicy/FinanceEXChatService 控制。</p>
 */
@Component
@ConditionalOnProperty(name = "financeex.use-case-library.provider", havingValue = "http")
public class HttpUseCaseLibraryClient implements UseCaseLibraryClient {
    private final WebClient webClient;
    private final String matchPath;
    private final Duration timeout;

    public HttpUseCaseLibraryClient(WebClient.Builder webClientBuilder,
                                    @Value("${financeex.use-case-library.base-url:http://localhost:9100}") String baseUrl,
                                    @Value("${financeex.use-case-library.match-path:/v1/use-cases/match}") String matchPath,
                                    @Value("${financeex.use-case-library.timeout:5s}") Duration timeout) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.matchPath = matchPath;
        this.timeout = timeout;
    }

    @Override
    public UseCaseMatchResult match(UseCaseMatchRequest request) {
        return webClient.post()
                .uri(matchPath)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(UseCaseMatchResult.class)
                .timeout(timeout)
                .blockOptional()
                .orElseGet(() -> UseCaseMatchResult.notMatched("empty use case response"));
    }
}
