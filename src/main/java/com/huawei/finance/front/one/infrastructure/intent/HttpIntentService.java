package com.huawei.finance.front.one.infrastructure.intent;

import com.huawei.finance.front.one.application.integration.intent.IntentService;
import com.huawei.finance.front.one.domain.auth.UserContext;
import com.huawei.finance.front.one.domain.chat.ChatCommand;
import com.huawei.finance.front.one.domain.intent.IntentDecision;
import com.huawei.finance.front.one.domain.intent.TaskComplexity;
import com.huawei.finance.front.one.domain.memory.MemoryContext;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 第三方意图服务 HTTP 适配器。
 *
 * <p>主控服务只依赖 IntentService 端口；HTTP 的地址、路径和超时留在基础设施配置中。
 * 更换第三方意图服务部署地址时，只需要调整 financeex.intent.base-url 和 recognize-path。</p>
 */
@Component
public class HttpIntentService implements IntentService {
    private final WebClient webClient;
    private final String recognizePath;
    private final Duration timeout;

    public HttpIntentService(WebClient.Builder webClientBuilder,
                             @Value("${financeex.intent.base-url:http://localhost:9200}") String baseUrl,
                             @Value("${financeex.intent.recognize-path:/v1/intents/recognize}") String recognizePath,
                             @Value("${financeex.intent.timeout:5s}") Duration timeout) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.recognizePath = recognizePath;
        this.timeout = timeout;
    }

    @Override
    public IntentDecision recognize(ChatCommand command, MemoryContext memory, UserContext user) {
        try {
            return webClient.post()
                    .uri(recognizePath)
                    .bodyValue(new IntentRecognizeRequest(
                            user.tenantId(),
                            user.userId(),
                            command.sessionId(),
                            command.message(),
                            command.attachments(),
                            command.metadata(),
                            memory
                    ))
                    .retrieve()
                    .bodyToMono(IntentDecision.class)
                    .timeout(timeout)
                    .blockOptional()
                    .orElseGet(() -> runtimeDecision("empty intent response"));
        } catch (RuntimeException ex) {
            return runtimeDecision("intent service failed: " + ex.getMessage());
        }
    }

    private IntentDecision runtimeDecision(String reason) {
        return new IntentDecision(
                "finance.runtime.degraded",
                "意图服务不可用，转入 AgentRuntime",
                TaskComplexity.COMPLEX,
                0.0,
                false,
                null,
                Map.of(),
                List.of(),
                Map.of("source", "http-intent-degraded", "reason", reason == null ? "" : reason)
        );
    }
}
